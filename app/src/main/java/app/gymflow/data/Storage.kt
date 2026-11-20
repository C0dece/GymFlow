package app.gymflow.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents", primaryKeys = ["scope", "id"])
data class Record(
    val scope: String,
    val id: String,
    val markdown: String,
    val pending: Boolean = true,
    val expected: String? = null,
    val source: String? = null,
    val backup: String? = null,
    val inFlight: String? = null,
    val conflict: String? = null,
    @ColumnInfo(defaultValue = "0") val localOnly: Boolean = false,
)

@Dao
interface Records {
    @Query("SELECT * FROM documents WHERE scope = :scope")
    fun observe(scope: String): Flow<List<Record>>

    @Query("SELECT * FROM documents WHERE scope = :scope")
    suspend fun all(scope: String): List<Record>

    @Query("SELECT * FROM documents WHERE scope = :scope AND id = :id")
    suspend fun get(scope: String, id: String): Record?

    @Query("SELECT * FROM documents WHERE scope = :scope AND pending AND NOT localOnly")
    suspend fun pending(scope: String): List<Record>

    @Query("DELETE FROM documents WHERE scope = :scope AND id = :id")
    suspend fun remove(scope: String, id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: Record)
}

@Database(entities = [Record::class], version = 2)
abstract class Journal : RoomDatabase() {
    abstract fun records(): Records
}

val journalMigration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN localOnly INTEGER NOT NULL DEFAULT 0")
    }
}

const val maxDocumentBytes = 1024 * 1024 / 2

data class Node(val id: String, val name: String, val folder: Boolean, val parent: String, val path: String = name)

class SafFolder(context: Context, private val tree: Uri) {
    private val resolver: ContentResolver = context.contentResolver
    val root: String = Docs.getTreeDocumentId(tree)
    private val directory = Docs.Document.MIME_TYPE_DIR
    private val mimeType = "text/markdown"

    private fun uri(id: String): Uri = Docs.buildDocumentUriUsingTree(tree, id)

    fun children(parent: String): List<Node> {
        val query = Docs.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
        return resolver.query(query, columns, null, null, null)?.use { cursor ->
            if (cursor.extras.getBoolean(Docs.EXTRA_LOADING)) throw IOException("Папка ещё загружается")
            buildList {
                while (cursor.moveToNext()) add(Node(cursor.getString(0), cursor.getString(1), cursor.getString(2) == directory, parent))
            }
        } ?: throw IOException("Папка недоступна")
    }

    fun create(parent: String, name: String, folder: Boolean = false): String {
        val document = Docs.createDocument(resolver, uri(parent), if (folder) directory else mimeType, name)
            ?: throw IOException("Не удалось создать $name")
        return Docs.getDocumentId(document)
    }

    fun read(id: String): String = readMarkdownFile(resolver, uri(id))

    fun rename(id: String, name: String): String =
        Docs.renameDocument(resolver, uri(id), name)?.let(Docs::getDocumentId)
            ?: throw IOException("Провайдер не подтвердил переименование")

    fun vaultId(create: Boolean, preferred: String? = null): String? {
        val markers = children(root).filter { it.name in setOf(vaultMarker, legacyVaultMarker) }
        if (markers.isEmpty() && !create) return null
        require(markers.size <= 1 && markers.none { it.folder }) { "Неоднозначный идентификатор папки GymFlow" }
        val existing = markers.singleOrNull()
        if (existing != null) {
            val text = read(existing.id).trim()
            val id = if (existing.name == legacyVaultMarker) text else text.lineSequence()
                .singleOrNull { it.startsWith("gymflow_vault: ") }?.removePrefix("gymflow_vault: ")
                ?: throw IOException("В маркере отсутствует ID папки GymFlow")
            require(runCatching { UUID.fromString(id).toString() }.getOrNull() == id) {
                "Повреждён идентификатор папки GymFlow"
            }
            return id
        }
        val id = preferred ?: UUID.randomUUID().toString()
        val marker = create(root, vaultMarker)
        val raw = vaultMetadata(id)
        write(marker, raw)
        check(read(marker) == raw) { "Не удалось проверить идентификатор папки" }
        return id
    }

    fun write(id: String, markdown: String) {
        resolver.openOutputStream(uri(id), "wt")?.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Не удалось открыть файл на запись")
    }
}

internal const val vaultMarker = "Хранилище GymFlow.md"
private const val legacyVaultMarker = "gymflow-vault.md"
internal fun vaultMetadata(id: String) = "---\ngymflow_vault: $id\n---\n\n# Хранилище GymFlow\n"

internal fun readMarkdownFile(resolver: ContentResolver, uri: Uri): String =
    resolver.openInputStream(uri)?.use(::readMarkdownStream) ?: throw IOException("Не удалось прочитать файл")

internal fun readMarkdownStream(stream: InputStream): String {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        if (bytes.size() + count > maxDocumentBytes) throw IOException("Файл превышает 512 КиБ")
        bytes.write(buffer, 0, count)
    }
    val raw = bytes.toByteArray()
    val value = raw.toString(Charsets.UTF_8)
    if (!value.toByteArray(Charsets.UTF_8).contentEquals(raw)) throw IOException("Ожидался UTF-8")
    return value
}

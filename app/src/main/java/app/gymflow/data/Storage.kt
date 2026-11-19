package app.gymflow.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import java.io.ByteArrayOutputStream
import java.io.IOException
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
)

@Dao
interface Records {
    @Query("SELECT * FROM documents WHERE scope = :scope")
    fun observe(scope: String): Flow<List<Record>>

    @Query("SELECT * FROM documents WHERE scope = :scope")
    suspend fun all(scope: String): List<Record>

    @Query("SELECT * FROM documents WHERE scope = :scope AND id = :id")
    suspend fun get(scope: String, id: String): Record?

    @Query("SELECT * FROM documents WHERE scope = :scope AND pending")
    suspend fun pending(scope: String): List<Record>

    @Query("SELECT DISTINCT scope FROM documents WHERE scope != ''")
    suspend fun folders(): List<String>

    @Query("DELETE FROM documents WHERE scope = :scope AND id = :id")
    suspend fun remove(scope: String, id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: Record)
}

@Database(entities = [Record::class], version = 1)
abstract class Journal : RoomDatabase() {
    abstract fun records(): Records
}

const val maxDocumentBytes = 1024 * 1024 / 2

data class Node(val id: String, val name: String, val folder: Boolean)

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
                while (cursor.moveToNext()) add(Node(cursor.getString(0), cursor.getString(1), cursor.getString(2) == directory))
            }
        } ?: throw IOException("Папка недоступна")
    }

    fun create(parent: String, name: String, folder: Boolean = false): String {
        val document = Docs.createDocument(resolver, uri(parent), if (folder) directory else mimeType, name)
            ?: throw IOException("Не удалось создать $name")
        return Docs.getDocumentId(document)
    }

    fun read(id: String): String = readMarkdownFile(resolver, uri(id))

    fun write(id: String, markdown: String) {
        resolver.openOutputStream(uri(id), "wt")?.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Не удалось открыть файл на запись")
    }
}

internal fun readMarkdownFile(resolver: ContentResolver, uri: Uri): String =
    resolver.openInputStream(uri)?.use { stream ->
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
        value
    } ?: throw IOException("Не удалось прочитать файл")

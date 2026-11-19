package app.gymflow.domain

import java.time.DayOfWeek
import java.time.LocalDate

internal val foundationProgramId = stableId("gymflow/foundation-8")

internal fun foundationProgram(catalog: List<Exercise>, today: LocalDate): Pair<Program, List<Exercise>> {
    val created = linkedMapOf<String, Exercise>()
    val shoulder = "При ограничениях плеча допустимость движения требует индивидуальной профессиональной оценки."
    val back = "При ограничениях поясницы допустимость варианта требует индивидуальной профессиональной оценки."

    fun exercise(name: String, load: Load, cardio: CardioType? = null, measure: Measure? = null): Exercise =
        catalog.find { it.name.equals(name, true) && it.load == load && it.cardio == cardio }
            ?: created.getOrPut(name) {
                Exercise(stableId("foundation/exercise/$name"), name, load, movement = measure?.let { MovementDetails(measures = setOf(it)) },
                    cardio = cardio)
            }

    fun strength(name: String, load: Load, count: Int, low: Int, high: Int, main: Boolean = false, caution: String = ""): ExercisePlan {
        val warmup = if (main) listOf(SetPlan(weightKnown = false, repsKnown = false, role = SetRole.RAMP)) else emptyList()
        return ExercisePlan(exercise(name, load), warmup + List(count) { SetPlan(repsMin = low, repsMax = high, weightKnown = false) },
            restSeconds = if (main) 180 else 90, caution = caution)
    }

    fun measured(name: String, cardio: CardioType? = null, measure: Measure? = null, role: SetRole = SetRole.WORK): ExercisePlan =
        ExercisePlan(exercise(name, Load.OTHER, cardio, measure), listOf(SetPlan(weightKnown = false, repsKnown = false, role = role)))

    fun day(index: Int, name: String, upper: Boolean, main: List<ExercisePlan>, accessory: List<ExercisePlan>,
        cardio: ExercisePlan, cardioGuidance: String, skill: ExercisePlan): WorkoutTemplate {
        fun block(type: BlockType, guidance: String = "", requirement: PlanBlock = PlanBlock.REQUIRED) =
            TrainingBlock(stableId("foundation/block/$index/${type.name}"), type, type.label, requirement, guidance)

        val warmup = block(BlockType.WARMUP, "5-7 минут. Выберите привычный вид разминки.", PlanBlock.RECOMMENDED)
        val prep = block(BlockType.PREPARATION, if (upper) "Подготовка плеч и подводящие подходы." else "Подготовка голеностопа и тазобедренных суставов.", PlanBlock.RECOMMENDED)
        val core = block(BlockType.MAIN, "Ориентир отдыха 2-4 минуты. Рабочий вес подбирается вручную.")
        val extra = block(BlockType.ACCESSORY, "Ориентир отдыха 1-2 минуты.")
        val aerobic = block(BlockType.CARDIO, cardioGuidance, PlanBlock.OPTIONAL)
        val mobility = block(BlockType.MOBILITY, "Дополнительная практика по желанию.", PlanBlock.OPTIONAL)
        val entries = listOf(
            warmup to listOf(measured("Общая разминка", measure = Measure.DURATION, role = SetRole.WARMUP)),
            prep to listOf(measured(if (upper) "Подготовка плеч" else "Подготовка голеностопа и таза", measure = Measure.DURATION, role = SetRole.TECHNIQUE)),
            core to main, extra to accessory, aerobic to listOf(cardio), mobility to listOf(skill),
        )
        return WorkoutTemplate(stableId("foundation/day/$index"), name, exercises = entries.flatMap { (block, plans) ->
            plans.map { it.copy(blockId = block.id, block = block.requirement) }
        }, blocks = entries.map { it.first })
    }

    val pull = strength("Подтягивания нейтральным хватом", Load.BODYWEIGHT, 4, 5, 8, main = true)
    val rope = strength("Разгибания рук с канатом", Load.MACHINE, 2, 10, 15)
    val legCurl = strength("Сгибания ног", Load.MACHINE, 3, 10, 15)
    val extend = strength("Разгибания ног", Load.MACHINE, 3, 10, 15)
    val calf = strength("Подъёмы на носки", Load.MACHINE, 4, 12, 20)
    val treadmill = measured("Ходьба на дорожке", CardioType.TREADMILL)
    val bike = measured("Велотренажёр", CardioType.BIKE)
    val balance = measured("Баланс на одной ноге", measure = Measure.BALANCE, role = SetRole.TECHNIQUE)
    val days = listOf(
        day(0, "Верх A", true,
            listOf(strength("Жим штанги лёжа", Load.BARBELL, 4, 5, 8, true, shoulder), pull),
            listOf(strength("Горизонтальная тяга блока", Load.MACHINE, 3, 8, 12), strength("Сгибания рук со штангой у стены", Load.BARBELL, 3, 8, 12),
                rope, strength("Махи гантелями в стороны", Load.DUMBBELL, 2, 12, 15)),
            treadmill, "Ходьба 15-20 минут.", measured("Hollow Hold", measure = Measure.HOLD, role = SetRole.TECHNIQUE)),
        day(1, "Низ A", false, listOf(strength("Жим ногами", Load.MACHINE, 4, 8, 12, true)),
            listOf(legCurl, extend, calf), bike, "Лёгкий велосипед 5-10 минут.", balance),
        day(2, "Верх B", true,
            listOf(strength("Жим гантелей сидя нейтральным хватом", Load.DUMBBELL, 3, 6, 10, true, shoulder), pull,
                strength("Наклонный жим гантелей под углом 30°", Load.DUMBBELL, 3, 8, 12, caution = shoulder)),
            listOf(strength("Обратная бабочка", Load.MACHINE, 3, 12, 15), strength("Сгибания гантелей на наклонной скамье", Load.DUMBBELL, 3, 10, 12), rope),
            bike, "Велосипед или дорожка 15-20 минут.", measured("Dead Bug", role = SetRole.TECHNIQUE)),
        day(3, "Низ B", false, listOf(strength("Приседания в допустимом варианте", Load.OTHER, 3, 6, 10, true, back)),
            listOf(strength("Сгибания ног", Load.MACHINE, 4, 10, 15), extend, calf),
            treadmill, "Лёгкая ходьба 5-10 минут.", balance),
    )
    val phase = listOf(
        CyclePhase(6, "Занятия 1-6", "Подбор текущей нагрузки, сокращённый объём, ориентир RIR 3-4."),
        CyclePhase(12, "Занятия 7-12", "Постепенный выход к основному объёму, ориентир RIR 2-3."),
        CyclePhase(18, "Занятия 13-18", "Ориентир RIR 1-3. Решения о нагрузке по фактическим результатам."),
        CyclePhase(24, "Занятия 19-24", "Контроль прогресса. Максимальные попытки необязательны."),
    )
    return Program(foundationProgramId, "Силовой фундамент 8", days, enabled = true, cycle = true, anchor = today.toString(),
        completionCycle = CompletionCycle(3, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), 24, phases = phase),
        caution = "При известных ограничениях плеча или поясницы учитывайте индивидуальную оценку допустимости силовых движений и сложных навыков.") to created.values.toList()
}

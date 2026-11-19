import { test } from 'node:test';
import assert from 'node:assert/strict';
import { convert, markdown } from './import-obsidian.mjs';

function fixture(week, plan, first, second) {
    const days = [
        ['Среда', '21', 'Тяга у блока'], ['Пятница', '23', 'Подтягивания'], ['Понедельник', '26', 'Жим гантелей'],
    ];
    return [
        '---', 'type: workout-log', 'week: ' + week, 'status: ' + (week === 1 ? 'done' : 'active'), '---', '',
        ...days.flatMap(([weekday, date, exercise], index) => [
            '## ' + weekday + ' (' + date + '.10.2026) - День ' + (index + 1) + ': ' + exercise,
            '| № | Упражнение | План | Подход 1 | Подход 2 |',
            '| 1 | **' + exercise + '** | ' + plan + ' | ' + first + ' | ' + second + ' |',
        ]),
    ].join('\n');
}

test('W44 becomes independent planned weights and W43 preserves only recorded values', () => {
    const w43 = fixture(1, '2×10', 'Ал: 12×9<br>П: ?×10', 'Ал: 18×?<br>П: ×');
    const w44 = fixture(2, '1р + 1×8-10', '*р* Ал: 12×<br>*р* П: 8×', 'Ал: 16×<br>П: 14×');
    const bundle = convert(w43, w44);
    assert.equal(bundle.program.days.length, 3);
    assert.equal(bundle.sessions.length, 3);
    const [alex, partner] = bundle.profiles;
    const plan = bundle.program.days[0].exercises[0];
    assert.equal(plan.individual[alex.id][1].weight, 16);
    assert.equal(plan.individual[partner.id][1].weight, 14);
    assert.equal(plan.individual[alex.id][0].repsKnown, false);
    assert.equal(plan.individual[alex.id][1].repsMin, 8);
    const marks = bundle.sessions[0].workouts.map(w => w.exercises[0].athletes[0].sets);
    assert.equal(marks[0][0].status, 'COMPLETED');
    assert.equal(marks[0][1].status, 'PLANNED');
    assert.equal(marks[0][1].actual.repsKnown, false);
    assert.equal(marks[1][0].actual.weightKnown, false);
    assert.equal(marks[1][1].actual, null);
    assert.equal(bundle.program.id, convert(w43, w44).program.id);
    assert.match(markdown(bundle.sessions[0]), /^---\ngymflow_schema: 3\n/);
});

test('a plan file cannot be substituted for completed history', () => {
    const w44 = fixture(2, '2×10', 'Ал: 12×<br>П: 10×', 'Ал: ×<br>П: ×');
    assert.throws(() => convert(w44, w44));
});

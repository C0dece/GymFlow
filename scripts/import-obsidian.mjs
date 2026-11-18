import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const weekdays = new Map([['Среда', 'WEDNESDAY'], ['Пятница', 'FRIDAY'], ['Понедельник', 'MONDAY']]);
const labels = new Map([['Ал', 'Алексей'], ['П', 'Партнёр']]);

function id(key) {
    const hex = createHash('md5').update('GymFlow:Obsidian:W43-W44:' + key).digest('hex');
    const variant = (Number.parseInt(hex[16], 16) & 3 | 8).toString(16);
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-3' + hex.slice(13, 16) +
        '-' + variant + hex.slice(17, 20) + '-' + hex.slice(20);
}

const people = [...labels].map(([code, name]) => ({ code, profile: { id: id('profile/' + code), name } }));

function readWeek(text, expected) {
    const normalized = text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
    const header = /^---\n([\s\S]+?)\n---\n/.exec(normalized)?.[1];
    if (!header || !new RegExp('^week: ' + expected + '$', 'm').test(header) ||
        !/^type: workout-log$/m.test(header) || (expected === 1 && !/^status: done$/m.test(header))) {
        throw new Error('Ожидалась исходная заметка недели ' + expected);
    }
    const days = [];
    let day, columns;
    for (const line of normalized.split('\n')) {
        const heading = /^## (Среда|Пятница|Понедельник) \((\d{2})\.(\d{2})\.(\d{4})\)\s+[-\u2013\u2014]\s+День (\d+):\s*(.+)$/.exec(line);
        if (heading) {
            const [, weekday, dd, mm, yyyy, number, title] = heading;
            day = { weekday: weekdays.get(weekday), date: yyyy + '-' + mm + '-' + dd, number, title, notes: [], rows: [] };
            days.push(day);
            columns = undefined;
        } else if (day && /^> \*\*(Разминка|Заминка):\*\*/.test(line)) {
            day.notes.push(line.slice(2).replaceAll('**', '').replace(/[\u2013\u2014]/g, '-'));
        } else if (day && line.trim().startsWith('|')) {
            const cells = line.trim().split('|').slice(1, -1).map(cell => cell.trim());
            if (cells[0] === '№') {
                columns = { name: cells.indexOf('Упражнение'), plan: cells.indexOf('План'),
                    sets: cells.map((cell, i) => /^Подход \d+$/.test(cell) ? i : -1).filter(i => i >= 0) };
                if (columns.name < 0 || columns.plan < 0 || !columns.sets.length) throw new Error('Неполный заголовок таблицы');
            } else if (columns && /^\d+[а-я]?$/.test(cells[0])) {
                if (cells.length <= columns.sets.at(-1)) throw new Error('Неполная строка упражнения');
                day.rows.push({ name: cells[columns.name].replace(/^\*\*|\*\*$/g, ''),
                    plan: cells[columns.plan], sets: columns.sets.map(i => cells[i]) });
            }
        }
    }
    if (days.length !== 3 || new Set(days.map(d => d.number)).size !== 3 || days.some(d => !d.rows.length)) {
        throw new Error('Ожидались три тренировочных дня с таблицами');
    }
    return days;
}

function readPlan(source) {
    const circles = /^(\d+)\s+круг[а-я]*$/.exec(source);
    if (circles) return { count: Number(circles[1]), warmups: 0, min: 1, max: 1, maxEffort: false, repsKnown: false };
    const match = /^(?:(\d+)р\s*\+\s*)?(\d+)\s*×\s*(макс|\d+)(?:[-\u2013\u2014](\d+))?$/.exec(source);
    if (!match) throw new Error('Не распознан план: ' + source);
    const [, warm, sets, min, max] = match;
    const maxEffort = min === 'макс';
    return { count: Number(sets) + Number(warm || 0), warmups: Number(warm || 0),
        min: maxEffort ? 1 : Number(min), max: maxEffort ? 1 : Number(max || min), maxEffort, repsKnown: !maxEffort };
}

function readCell(cell, code) {
    const row = cell.split(/<br\s*\/?>/i).find(part => new RegExp('(?:^|\\s)' + code + ':').test(part));
    if (!row) return { weightKnown: false, repsKnown: false, weight: 0, reps: 1, note: '', warmup: false };
    const content = row.slice(row.indexOf(':') + 1).trim();
    const match = /^(св|\d+(?:[.,]\d+)?|\?)?\s*×\s*(\d+|\?)?(.*)$/i.exec(content);
    if (!match) throw new Error('Не распознан подход: ' + content);
    const [, weight, reps, note] = match;
    return {
        weightKnown: !!weight && weight !== '?', repsKnown: !!reps && reps !== '?',
        weight: weight === 'св' || !weight || weight === '?' ? 0 : Number(weight.replace(',', '.')),
        reps: !reps || reps === '?' ? 1 : Number(reps),
        note: note.trim().replace(/[\u2013\u2014]/g, '-'), warmup: row.includes('*р*'),
    };
}

function loadType(name) {
    if (/подтягив|ног в висе|гиперэкстензи/i.test(name)) return 'BODYWEIGHT';
    if (/штанг/i.test(name)) return 'BARBELL';
    if (/жим гантелей|гантели наклон|махи|жим гантелями/i.test(name)) return 'DUMBBELL';
    if (/блок|кроссовер|платформ|разгибания ног|сгибания ног/i.test(name)) return 'MACHINE';
    return 'OTHER';
}

function setsFor(row, prescription, code, historical) {
    return Array.from({ length: prescription.count }, (_, index) => {
        const raw = readCell(row.sets[index] ?? '', code);
        const warmup = raw.warmup || index < prescription.warmups;
        const plan = {
            weight: historical ? 0 : raw.weight, weightKnown: !historical && raw.weightKnown, warmup,
            repsMin: warmup ? 1 : prescription.min, repsMax: warmup ? 1 : prescription.max,
            maxEffort: !warmup && prescription.maxEffort, repsKnown: !warmup && prescription.repsKnown,
        };
        const recorded = historical && (raw.repsKnown || raw.weightKnown);
        const actual = recorded ? { weight: raw.weight, reps: raw.reps,
            note: raw.repsKnown ? raw.note : 'Записан вес. Повторы неизвестны.',
            weightKnown: raw.weightKnown, repsKnown: raw.repsKnown } : null;
        return { plan, actual };
    });
}

export function convert(week1, week2) {
    const history = readWeek(week1, 1);
    const planned = readWeek(week2, 2);
    const catalog = new Map();
    for (const day of [...planned, ...history]) for (const row of day.rows) {
        if (catalog.has(row.name)) continue;
        const plan = readPlan(row.plan);
        catalog.set(row.name, { kind: 'exercise', id: id('exercise/' + row.name), name: row.name,
            load: loadType(row.name), repsMin: plan.min, repsMax: plan.max });
    }
    const snapshot = name => {
        const { kind, ...exercise } = catalog.get(name);
        return exercise;
    };
    const templateId = n => id('template/' + n);
    const program = {
        kind: 'program', id: id('program'), title: 'Возвращение в зал',
        enabled: true, anchor: planned[0].date, participantIds: [people[0].profile.id],
        days: planned.map(day => ({
            id: templateId(day.number), title: day.title, weekday: day.weekday, notes: day.notes.join('\n'),
            exercises: day.rows.map(row => {
                const prescription = readPlan(row.plan);
                const individual = Object.fromEntries(people.map(({ code, profile }) =>
                    [profile.id, setsFor(row, prescription, code, false).map(set => set.plan)]));
                return { exercise: snapshot(row.name), sets: individual[people[0].profile.id], individual };
            }),
        })),
    };
    const sessions = history.map(day => ({
        kind: 'session', id: id('session/' + day.date), title: day.title, date: day.date, status: 'COMPLETED',
        programId: program.id, programDayId: templateId(day.number),
        notes: day.rows.some(row => row.sets.some(cell => cell === 'Выполнено'))
            ? 'В W43 суперсет ног отмечен выполненным. Индивидуальные веса и повторы не указаны.' : '',
        workouts: people.map(({ code, profile }) => ({
            id: id('workout/' + day.date + '/' + code), profile, templateId: templateId(day.number),
            templateTitle: day.title, status: 'COMPLETED',
            exercises: day.rows.map((row, index) => {
                const exercise = snapshot(row.name);
                const key = day.date + '/' + code + '/' + index;
                const sets = setsFor(row, readPlan(row.plan), code, true).map((set, i) => ({
                    id: id('set/' + key + '/' + i), ...set, status: set.actual?.repsKnown ? 'COMPLETED' : 'PLANNED',
                }));
                return { id: id('entry/' + key), plan: { exercise, sets: sets.map(set => set.plan) },
                    athletes: [{ participantId: profile.id, sets }] };
            }),
        })),
    }));
    return { program, sessions, catalog: [...catalog.values()], profiles: people.map(({ profile }) => ({ kind: 'profile', ...profile })) };
}

function escape(value) {
    return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
        .replaceAll('|', '&#124;').replace(/[\r\n]/g, ' ');
}
function planText(set) {
    const reps = set.maxEffort ? 'макс' : !set.repsKnown ? '?' :
        set.repsMin === set.repsMax ? set.repsMax : set.repsMin + '-' + set.repsMax;
    return (set.weightKnown ? set.weight : '?') + ' × ' + reps;
}

export function markdown(model) {
    const header = ['---', 'gymflow_schema: 2', 'id: ' + model.id, 'kind: ' + model.kind];
    const report = ['# ' + escape(model.title ?? model.name), ''];
    if (model.kind === 'session') {
        header.push('date: ' + model.date, 'status: completed', 'participants: [' + model.workouts.map(w => w.profile.id).join(', ') + ']');
        report.push('> [!info] ' + model.date, '', model.notes);
        for (const workout of model.workouts) {
            report.push('', '## ' + escape(workout.profile.name));
            for (const entry of workout.exercises) {
                report.push('', '### ' + escape(entry.plan.exercise.name), '',
                    '| Подход | План | Факт | Заметка |', '| --- | --- | --- | --- |');
                entry.athletes[0].sets.forEach((set, index) => {
                    const a = set.actual;
                    const actual = a ? (a.weightKnown ? a.weight : '?') + ' × ' + (a.repsKnown ? a.reps : '?') : '';
                    report.push('| ' + (set.plan.warmup ? 'Разминка' : index + 1) + ' | ' + planText(set.plan) +
                        ' | ' + actual + ' | ' + escape(a?.note ?? '') + ' |');
                });
            }
        }
    } else if (model.kind === 'program') {
        for (const day of model.days) {
            report.push('## ' + escape(day.title), '', day.notes, '');
            for (const plan of day.exercises) {
                report.push('- **' + escape(plan.exercise.name) + '**');
                for (const { profile } of people) {
                    report.push('  - ' + profile.name + ': ' + plan.individual[profile.id].map(planText).join(', '));
                }
            }
            report.push('');
        }
    }
    return [...header, '---', '', '<!-- gymflow:report -->', ...report, '<!-- /gymflow:report -->', '',
        '<!-- gymflow:json', JSON.stringify(model, null, 2).replaceAll('-->', '--\\u003e'), 'gymflow:json -->', ''].join('\n');
}

function main() {
    const [week1, week2, output] = process.argv.slice(2);
    if (!week1 || !week2 || !output) {
        throw new Error('Синтаксис: node scripts/import-obsidian.mjs <W43.md> <W44.md> <новая папка GymFlow>');
    }
    const target = resolve(output);
    if (existsSync(target)) throw new Error('Выходная папка уже существует. Выберите новую папку.');
    const result = convert(readFileSync(week1, 'utf8'), readFileSync(week2, 'utf8'));
    const groups = { 'Программы': [result.program], 'Тренировки': result.sessions,
        'Упражнения': result.catalog, 'Участники': result.profiles };
    const staging = target + '.importing-' + process.pid;
    mkdirSync(dirname(target), { recursive: true });
    mkdirSync(staging);
    try {
        for (const [folder, documents] of Object.entries(groups)) {
            mkdirSync(join(staging, folder));
            for (const document of documents) {
                writeFileSync(join(staging, folder, document.id + '.md'), markdown(document), { encoding: 'utf8', flag: 'wx' });
            }
        }
        renameSync(staging, target);
    } catch (failure) {
        rmSync(staging, { recursive: true, force: true });
        throw failure;
    }
    const actuals = result.sessions.flatMap(s => s.workouts).flatMap(w => w.exercises)
        .flatMap(e => e.athletes[0].sets).filter(s => s.actual);
    const confirmed = actuals.filter(set => set.status === 'COMPLETED').length;
    console.log('Готово: ' + result.program.days.length + ' дня, ' + result.catalog.length + ' упражнений, ' +
        result.sessions.length + ' исторические сессии, ' + confirmed + ' подтверждённых подходов, ' +
        (actuals.length - confirmed) + ' записей веса без подтверждённых повторов.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();

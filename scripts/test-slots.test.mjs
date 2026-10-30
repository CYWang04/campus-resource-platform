import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { testSchedule } from './test-slots.mjs';

for (const date of ['2026-10-24T07:10:14Z', '2026-10-26T15:59:59Z', '2026-12-31T16:01:00Z']) {
  test(`all smoke intervals are future/open for ${date}`, () => {
    const now = Date.parse(date);
    const { at } = testSchedule(now);
    assert.ok(Date.parse(at(0)) > now);
    assert.ok(Date.parse(at(13)) < now + 7 * 86400000);
    for (let i = 0; i <= 13; i++) {
      const local = new Date(Date.parse(at(i)) + 8 * 3600000);
      assert.equal(local.getUTCHours(), 8 + i);
      assert.equal(local.getUTCMinutes(), 0);
    }
  });
}

test('schedule does not depend on runner TZ', () => {
  const source = "import {testSchedule} from './scripts/test-slots.mjs'; console.log(testSchedule(Date.parse('2026-10-24T07:10:14Z')).at(9));";
  const results = ['UTC', 'Asia/Shanghai', 'America/New_York'].map(TZ => {
    const run = spawnSync(process.execPath, ['--input-type=module', '-e', source], { env: { ...process.env, TZ }, encoding: 'utf8' });
    assert.equal(run.status, 0, run.stderr);
    return run.stdout.trim();
  });
  assert.deepEqual(results, Array(3).fill('2026-10-25T09:00:00.000Z'));
});

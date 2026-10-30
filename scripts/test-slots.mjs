const hour = 3600000;
const shanghaiOffset = 8 * hour;

// Always start at 08:00 Shanghai tomorrow; all smoke offsets 0..13 remain open.
export function testSchedule(now = Date.now()) {
  const local = new Date(now + shanghaiOffset);
  const start = Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate() + 1) ;
  return {
    nextHour: new Date(start),
    at: (offset) => new Date(start + offset * hour).toISOString(),
  };
}

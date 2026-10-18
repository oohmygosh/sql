export const timePresets = {
  yesterday: { from: 'now-1d/d', to: 'now/d' },
  today: { from: 'now/d', to: 'now' },
  '1d': { from: 'now-24h', to: 'now' },
  '7d': { from: 'now-7d', to: 'now' },
};

// Picker values are wall-clock times in the query's business timezone.
export function toDateTimeInput(value, timeZone) {
  const date = new Date(value);
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(date).map(part => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}.${String(date.getUTCMilliseconds()).padStart(3, '0')}`;
}

export function validatePickerTimeRange(from, to) {
  const parse = value => {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?$/.test(value)) {
      throw new Error('请选择有效的开始和结束时间，时间仅支持毫秒精度。');
    }
    // Compare wall-clock values without borrowing the browser's timezone.
    const timestamp = Date.parse(`${value}Z`);
    const seconds = value.length === 16 ? `${value}:00` : value;
    const normalized = seconds.includes('.') ? seconds.padEnd(23, '0') : `${seconds}.000`;
    if (!Number.isFinite(timestamp) || new Date(timestamp).toISOString().slice(0, -1) !== normalized) {
      throw new Error('请选择有效的日期和时间。');
    }
    return timestamp;
  };
  if (parse(from) >= parse(to)) throw new Error('开始时间必须早于结束时间。');
  return { from, to };
}

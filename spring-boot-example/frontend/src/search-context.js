// Keep the successful search independent of editable controls and export tasks.
export function copyContext(context) {
  return JSON.parse(JSON.stringify(context));
}
export function freezeContext(context, resolvedTimeRange) {
  const snapshot = copyContext(context);
  if (context.timeRange) {
    if (!resolvedTimeRange || !Number.isFinite(Date.parse(resolvedTimeRange.from)) || !Number.isFinite(Date.parse(resolvedTimeRange.to))) {
      throw new Error('服务端未返回已解析的绝对时间范围，无法安全翻页或导出。');
    }
    snapshot.timeRange = copyContext(resolvedTimeRange);
  }
  const freeze = value => {
    if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
    return value;
  };
  return freeze(snapshot);
}
export function searchBody(context, number, size) {
  return { ...copyContext(context), page: { number, size }, include: ['rows'] };
}
export function histogramBody(context) {
  return { ...copyContext(context), histogram: { interval: 'auto' }, include: ['histogram'] };
}
export function validateAbsoluteTimeRange(from, to) {
  const precise = value => {
    const fraction = /\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/i.exec(value)?.[1];
    return !fraction || !/[1-9]/.test(fraction.slice(3));
  };
  if (!precise(from) || !precise(to)) throw new Error('绝对时间仅支持毫秒精度，请移除亚毫秒部分。');
  if (!/(?:Z|[+-]\d{2}:\d{2})$/i.test(from) || !/(?:Z|[+-]\d{2}:\d{2})$/i.test(to)
      || !Number.isFinite(Date.parse(from)) || !Number.isFinite(Date.parse(to)) || Date.parse(from) >= Date.parse(to)) {
    throw new Error('绝对时间请使用带时区的 ISO 格式，且开始时间早于结束时间。');
  }
  // Preserve the original ISO text so the server remains the authority on date validity.
  return { from, to };
}
export function parseFilterValue(text, type, operator) {
  if (['is_null', 'is_not_null'].includes(operator)) return undefined;
  if (operator === 'in') {
    let values;
    try { values = JSON.parse(text); } catch { throw new Error('in 的值请填写 JSON 数组，例如 ["ERROR", "WARN"]。'); }
    if (!Array.isArray(values) || !values.length) throw new Error('in 的值必须是非空 JSON 数组。');
    if (values.some(value => value === null)) throw new Error('in 数组不支持 null 元素。');
    if (values.some(value => !['string', 'number', 'boolean'].includes(typeof value))) throw new Error('in 数组仅支持字符串、数字或布尔值。');
    if (values.some(value => typeof value === 'number' && (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value))))) throw new Error('数组中的整数超出浏览器安全范围，请在原始语句中填写精确值。');
    return values;
  }
  if (/^(?:byte|short|int|integer|long|bigint|float|double|decimal|number|numeric)$/i.test(type)) {
    const value = Number(text);
    if (!text.trim() || !Number.isFinite(value)) throw new Error('数值字段请输入有效数字。');
    if (/^(?:byte|short|int|integer|long|bigint)$/i.test(type) && !Number.isSafeInteger(value)) throw new Error('整数超出浏览器安全范围，请在原始语句中填写精确值。');
    return value;
  }
  if (/^bool(?:ean)?$/i.test(type)) {
    if (!['true', 'false'].includes(text)) throw new Error('布尔字段请输入 true 或 false。');
    return text === 'true';
  }
  return text;
}

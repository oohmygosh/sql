const ingestionFields = new Set([
  'collectionmethod', 'collectormetadata', 'ingesttime', 'inputtype',
  'instanceuuid', 'logconfigid', 'parsesuccess',
]);
const metadataFields = new Set([
  ...ingestionFields, 'host', 'hostip', 'indexdate', 'id', 'priority', 'size',
]);

// The collection envelope identifies a complete LogHub record. A projection of
// message plus an incidental ingestion field must keep its explicit columns.
export function getLogLayout(schema) {
  const names = schema.map(column => column.name.toLowerCase());
  if (new Set(names).size !== names.length || !names.includes('collectormetadata') || !names.includes('collectionmethod')) return null;
  const find = candidates => {
    for (const name of candidates) {
      const index = names.indexOf(name);
      if (index !== -1) return index;
    }
    return -1;
  };
  const bodyIndex = find(['message', 'msg', 'content', 'log.original']);
  if (bodyIndex === -1) return null;
  const layout = {
    timeIndex: find(['timestamp', '@timestamp', 'time', 'datetime']),
    bodyIndex,
    extractedIndex: find(['extracted']),
    levelIndex: find(['level', 'log.level', 'severity']),
    sourceIndex: find(['source', 'service', 'service.name']),
    hostIndex: find(['hostip', 'host.name', 'hostname', 'host']),
  };
  const summaryIndexes = new Set(Object.values(layout));
  layout.metadataIndexes = names.flatMap((name, index) => metadataFields.has(name) && !summaryIndexes.has(index) ? [index] : []);
  return layout;
}

export function extractedEntries(value) {
  if (!value || typeof value !== 'object') return [];
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null ? Object.entries(value) : [];
}

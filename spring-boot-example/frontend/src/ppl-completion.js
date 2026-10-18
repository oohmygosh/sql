import { ATN, ATNDeserializer, CharStream, CommonTokenStream, LexerInterpreter, ParserInterpreter, Token, Vocabulary } from 'antlr4ng';
import { CodeCompletionCore } from 'antlr4-c3';
import { quoteIdentifier } from './results.js';

const PREFERRED_RULES = ['tableQualifiedName', 'qualifiedName', 'wcQualifiedName', 'keywordsCanBeId', 'searchableKeyWord', 'comparisonOperator', 'searchComparisonOperator'];
// ANTLR does not publish literal names for lexer rules written as multiple character literals.
// These spellings are offered only when c3 reaches the grammar's comparisonOperator rule.
const COMPARISON_TEXT = { EQUAL: '=', NOT_EQUAL: '!=', GREATER: '>', NOT_LESS: '>=', LESS: '<', NOT_GREATER: '<=' };

function integers(values, name) {
  if (!Array.isArray(values)) throw new Error(`PPL 语法包缺少 ${name}`);
  return values.map(value => {
    if (value === null || typeof value === 'boolean' || typeof value === 'string' && !value.trim()) throw new Error(`PPL 语法包 ${name} 包含无效整数`);
    const number = Number(value);
    if (!Number.isSafeInteger(number)) throw new Error(`PPL 语法包 ${name} 包含无效整数`);
    return number;
  });
}

function names(values, name, sparse = false) {
  if (!Array.isArray(values) || values.some(value => typeof value !== 'string' && !(sparse && value === null))) {
    throw new Error(`PPL 语法包 ${name} 无效`);
  }
  return values.slice();
}

// The runtime counts Unicode code points; textarea selection and setRangeText use UTF-16.
function codePointOffsets(text) {
  const offsets = [0];
  for (const character of text) offsets.push(offsets.at(-1) + character.length);
  return offsets;
}

function quotedRange(text, start, quote) {
  let position = start + 1;
  while (position < text.length) {
    if (text[position] === '\\') { position += 2; continue; }
    if (text[position] === quote) {
      if (text[position + 1] === quote) { position += 2; continue; }
      return { end: position + 1, closed: true };
    }
    position++;
  }
  return { end: text.length, closed: false };
}

function cursorContext(text, caret, tokens, offsets, symbolicNames) {
  const comment = tokens.find(token => ['LINE_COMMENT', 'BLOCK_COMMENT'].includes(symbolicNames[token.type]) &&
    caret > offsets[token.start] && (caret < offsets[token.stop + 1] ||
      symbolicNames[token.type] === 'LINE_COMMENT' && !/[\r\n]$/.test(token.text) && caret === offsets[token.stop + 1]));
  if (comment) return null;
  if (tokens.some(token => symbolicNames[token.type]?.endsWith('_LITERAL') &&
    caret >= offsets[token.start] && caret <= offsets[token.stop + 1])) return null;
  for (let position = 0; position < text.length;) {
    const start = position, character = text[position];
    if (text.startsWith('//', position)) {
      const newline = text.indexOf('\n', position + 2);
      position = newline < 0 ? text.length : newline;
      if (caret > start && caret <= position) return null;
    } else if (text.startsWith('/*', position)) {
      const closing = text.indexOf('*/', position + 2);
      position = closing < 0 ? text.length : closing + 2;
      if (caret > start && (caret < position || closing < 0 && caret === position)) return null;
    } else if (character === "'" || character === '"' || character === '`') {
      const range = quotedRange(text, position, character);
      position = range.end;
      const closed = range.closed;
      if (caret > start && (caret < position || !closed && caret === position)) {
        return character === '`' ? { from: start, to: position } : null;
      }
      if (character === '`' && caret === start) return { from: start, to: position };
      if (character === '`' && caret === position) return { from: start, to: position };
    } else position++;
    if (position > caret) break;
  }
  const wordTokens = tokens.filter(token => token.type !== Token.EOF && token.channel === Token.DEFAULT_CHANNEL &&
    !symbolicNames[token.type]?.endsWith('_LITERAL') &&
    /^[\p{L}\p{N}_@]/u.test(token.text || ''));
  const current = wordTokens.find(token => offsets[token.start] <= caret && caret < offsets[token.stop + 1]) ||
    wordTokens.find(token => offsets[token.start] < caret && caret === offsets[token.stop + 1]);
  return current ? { from: offsets[current.start], to: offsets[current.stop + 1] } : { from: caret, to: caret };
}

function unquoteIdentifier(value) {
  return value.replace(/`((?:``|\\.|[^`])*)`?/g, (_, part) => part.replaceAll('``', '`').replace(/\\(.)/g, '$1'));
}

function qualifiedIdentifierEnd(tokens, offsets, symbolicNames, end) {
  const following = tokens.filter(token => token.type !== Token.EOF && token.channel === Token.DEFAULT_CHANNEL && offsets[token.start] >= end);
  for (let index = 0; index + 1 < following.length && symbolicNames[following[index].type] === 'DOT'; index += 2) {
    const token = following[index + 1];
    if (symbolicNames[token.type] !== 'BQUOTA_STRING' && !/^[\p{L}\p{N}_@]/u.test(token.text || '')) break;
    end = offsets[token.stop + 1];
  }
  return end;
}

function configuredFields(tree, parserRules, config) {
  if (!config || typeof config.table !== 'string' || !config.table) return [];
  // SOURCE and INDEX can also name expression fields. The interpreted tree distinguishes them
  // from actual source clauses and counts tables for every JOIN form without a command list.
  const sources = [], tables = [];
  let hasJoin = false;
  const visit = (node, parents = []) => {
    const name = parserRules[node.ruleIndex];
    if (!name) return;
    if (name === 'fromClause') sources.push(node);
    if (name === 'tableSource') tables.push({ node, parents });
    if (name === 'joinCommand') hasJoin = true;
    for (const child of node.children || []) visit(child, [...parents, name]);
  };
  visit(tree);
  if (hasJoin || sources.length !== 1 || tables.length !== 1 || !tables[0].parents.includes('fromClause') ||
    tables[0].parents.includes('subSearch') || unquoteIdentifier(tables[0].node.getText()) !== config.table) return [];
  return [...new Set([...(Array.isArray(config.sourceFields) ? config.sourceFields.map(field => field?.name) : []), config.timeField])]
    .filter(field => typeof field === 'string' && field.length > 0);
}

function whereFieldAtCaret(tree, parserRules, text, caret, offsets) {
  let field;
  const visit = (node, parents = []) => {
    const name = parserRules[node.ruleIndex];
    if (!name) return;
    if (name === 'fieldExpression' && parents.some(parent => parserRules[parent.ruleIndex] === 'whereCommand')) {
      const logical = parents.findLast(parent => parserRules[parent.ruleIndex] === 'logicalExpression');
      const end = offsets[node.stop?.stop + 1];
      // A bare predicate field needs a comparison, whereas a field on the right of an
      // existing comparison or inside a finished function must not reopen suggestions.
      if (logical?.start?.tokenIndex === node.start?.tokenIndex && logical?.stop?.tokenIndex === node.stop?.tokenIndex &&
        end <= caret && !text.slice(end, caret).trim()) field = node;
    }
    for (const child of node.children || []) visit(child, [...parents, node]);
  };
  visit(tree);
  return field;
}

export function createPplCompletion(bundle) {
  if (!bundle || bundle.bundleVersion !== '1.0' || !/^4\.13\./.test(bundle.antlrVersion || '')) {
    throw new Error('不支持的 PPL 语法包版本');
  }
  if (integers([bundle.startRuleIndex], 'startRuleIndex')[0] !== 0) throw new Error('PPL 语法包必须以 root (0) 开始');
  const lexerData = integers(bundle.lexerSerializedATN, 'lexerSerializedATN');
  const parserData = integers(bundle.parserSerializedATN, 'parserSerializedATN');
  if (lexerData[0] !== ATNDeserializer.SERIALIZED_VERSION || parserData[0] !== ATNDeserializer.SERIALIZED_VERSION) {
    throw new Error('不支持的 PPL ATN 版本');
  }
  const lexerATN = new ATNDeserializer().deserialize(lexerData);
  const parserATN = new ATNDeserializer().deserialize(parserData);
  if (lexerATN.grammarType !== ATN.LEXER || parserATN.grammarType !== ATN.PARSER) throw new Error('PPL ATN 类型无效');
  const lexerRules = names(bundle.lexerRuleNames, 'lexerRuleNames');
  const parserRules = names(bundle.parserRuleNames, 'parserRuleNames');
  const channelNames = names(bundle.channelNames, 'channelNames');
  const modeNames = names(bundle.modeNames, 'modeNames');
  const literalNames = names(bundle.literalNames, 'literalNames', true);
  const symbolicNames = names(bundle.symbolicNames, 'symbolicNames', true);
  const vocabulary = new Vocabulary(literalNames, symbolicNames);
  const visitRules = new Set(integers(bundle.rulesToVisit, 'rulesToVisit'));
  const preferredRules = new Set(PREFERRED_RULES.map(name => parserRules.indexOf(name)).filter(index => index >= 0 && visitRules.has(index)));
  const ignoredTokens = new Set([...integers(bundle.ignoredTokens, 'ignoredTokens'), Token.EOF]);
  const comparisonTokens = new Set();
  for (const name of ['comparisonOperator', 'searchComparisonOperator']) {
    const index = parserRules.indexOf(name);
    if (index >= 0) for (const type of parserATN.nextTokens(parserATN.ruleToStartState[index]).toArray()) comparisonTokens.add(type);
  }
  const functionTokens = new Set();
  parserRules.forEach((name, index) => {
    if (/(?:FunctionName|FunctionCall|AggFunction)$/.test(name) || ['statsFunction', 'windowFunction'].includes(name)) {
      for (const type of parserATN.nextTokens(parserATN.ruleToStartState[index]).toArray()) functionTokens.add(type);
    }
  });
  // c3 caches follow sets by constructor.name, so dynamic grammar instances need their own identity.
  class PplParser extends ParserInterpreter {}
  Object.defineProperty(PplParser, 'name', { value: `PplParser_${bundle.grammarHash || parserData.join('_')}` });

  return {
    complete(text, caretUtf16, sourceConfig) {
      if (typeof text !== 'string' || !Number.isInteger(caretUtf16) || caretUtf16 < 0 || caretUtf16 > text.length) return [];
      const offsets = codePointOffsets(text);
      if (!offsets.includes(caretUtf16)) return [];
      const lexer = new LexerInterpreter('OpenSearchPPLLexer', vocabulary, lexerRules, channelNames, modeNames, lexerATN, CharStream.fromString(text));
      lexer.removeErrorListeners();
      const stream = new CommonTokenStream(lexer);
      stream.fill();
      const tokens = stream.getTokens();
      let cursor = cursorContext(text, caretUtf16, tokens, offsets, symbolicNames);
      if (!cursor) return [];
      const parser = new PplParser('OpenSearchPPLParser', vocabulary, parserRules, parserATN, stream);
      parser.removeErrorListeners();
      const tree = parser.parse(0);
      const fields = configuredFields(tree, parserRules, sourceConfig);
      const field = whereFieldAtCaret(tree, parserRules, text, caretUtf16, offsets);
      const needsComparison = field && (cursor.from === cursor.to || fields.includes(unquoteIdentifier(field.getText())));
      if (needsComparison) cursor = { from: caretUtf16, to: caretUtf16 };
      const caretToken = tokens.find(token => token.channel === Token.DEFAULT_CHANNEL &&
        (token.type === Token.EOF || offsets[token.start] >= cursor.from));
      // c3 returns possible continuations, including clauses which still need arguments. Once
      // a query can end here, wait for an explicit new prefix instead of offering those fragments.
      const lastToken = tokens.filter(token => token.channel === Token.DEFAULT_CHANNEL && token.type !== Token.EOF).at(-1);
      if (!needsComparison && cursor.from === cursor.to && !text.slice(caretUtf16).trim() && symbolicNames[lastToken?.type] !== 'PIPE' &&
        parser.numberOfSyntaxErrors === 0 && !tokens.some(token =>
          symbolicNames[token.type] === 'ERROR_RECOGNITION' && /\S/u.test(token.text || ''))) return [];
      parser.reset();
      const core = new CodeCompletionCore(parser);
      core.ignoredTokens = ignoredTokens;
      core.preferredRules = preferredRules;
      const candidates = core.collectCandidates(caretToken.tokenIndex);
      const result = [];
      const previous = tokens.filter(token => token.type !== Token.EOF && token.channel === Token.DEFAULT_CHANNEL && offsets[token.stop + 1] <= cursor.from).at(-1);
      const commandStart = !previous || symbolicNames[previous.type] === 'PIPE';
      const inWhere = rule => rule.ruleList.some(index => parserRules[index] === 'whereCommand');
      const fieldsOnly = cursor.from === caretUtf16 && ['WHERE', 'AND', 'OR', 'XOR', 'NOT'].includes(symbolicNames[previous?.type]) &&
        [...candidates.rules].some(([index, rule]) => parserRules[index] === 'qualifiedName' && inWhere(rule));
      // The right operand can be any user value. Do not reopen the field/function menu
      // immediately after the comparison; an explicit value prefix can still be completed.
      if (cursor.from === cursor.to && comparisonTokens.has(previous?.type) && [...candidates.rules.values()].some(inWhere)) return [];
      const add = (label, insertText, from, to, kind, trailingSpace = false) => {
        if (fieldsOnly && kind !== 'field') return;
        if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || from > caretUtf16 || to < caretUtf16 || to > text.length) return;
        const prefix = unquoteIdentifier(text.slice(from, caretUtf16)).toLowerCase();
        if (!label.toLowerCase().startsWith(prefix)) return;
        if (kind !== 'function' && caretUtf16 >= to && unquoteIdentifier(text.slice(from, to)).toLowerCase() === label.toLowerCase()) return;
        const before = text.slice(0, from), after = text.slice(to);
        if (/[\p{L}\p{N}_@`'"\)\]]$/u.test(before) && (kind === 'operator' || /^[\p{L}_@`]/u.test(insertText))) insertText = ' ' + insertText;
        if ((kind === 'keyword' || kind === 'operator' || trailingSpace) && !/^\s/.test(after)) insertText += ' ';
        result.push({ label, insertText, from, to, kind });
      };
      if (needsComparison) {
        for (const [index] of candidates.rules) {
          if (!['comparisonOperator', 'searchComparisonOperator'].includes(parserRules[index])) continue;
          for (const type of parserATN.nextTokens(parserATN.ruleToStartState[index]).toArray()) {
            const label = COMPARISON_TEXT[symbolicNames[type]];
            if (label) add(label, label, cursor.from, cursor.to, 'operator');
          }
        }
        return result.sort((left, right) => left.label.localeCompare(right.label));
      }
      for (const [type] of candidates.tokens) {
        const literal = literalNames[type];
        if (!literal || literal.length < 3 || literal[0] !== "'" || literal.at(-1) !== "'") continue;
        const label = literal.slice(1, -1).toLowerCase();
        if (!/[a-z]/i.test(label)) continue;
        add(label, label, cursor.from, cursor.to, !commandStart && functionTokens.has(type) ? 'function' : 'keyword');
      }
      const identifierTo = qualifiedIdentifierEnd(tokens, offsets, symbolicNames, cursor.to);
      for (const [index, rule] of candidates.rules) {
        const name = parserRules[index];
        const from = offsets[tokens[rule.startTokenIndex].start];
        if (name === 'tableQualifiedName' && typeof sourceConfig?.table === 'string' && sourceConfig.table) {
          add(sourceConfig.table, quoteIdentifier(sourceConfig.table), from, identifierTo, 'table');
        } else if (name === 'qualifiedName' || name === 'wcQualifiedName') {
          for (const field of fields) add(field, quoteIdentifier(field), from, identifierTo, 'field', inWhere(rule));
        }
      }
      // Fields are added last so configured metadata wins over an identically named
      // grammar function (for example timestamp) at the same edit range.
      return [...new Map(result.map(item => [`${item.label.toLowerCase()}:${item.from}:${item.to}`, item])).values()]
        .sort((left, right) => Number(right.kind === 'field') - Number(left.kind === 'field') || left.label.localeCompare(right.label));
    },
  };
}

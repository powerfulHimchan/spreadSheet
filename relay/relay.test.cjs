const {test} = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(__dirname + '/Code.gs', 'utf8');
const ID = 'test_spreadsheet_id_123456789';
const KEY = 'a'.repeat(43);
const copy = value => JSON.parse(JSON.stringify(value));

function fixture() {
  const properties = {FAMILY_KEY: KEY, ALLOWED_SPREADSHEET_IDS: ID};
  const sheet = {properties: {sheetId: 7, title: "가족's 장부", gridProperties: {rowCount: 20, columnCount: 8}},
    data: [{rowData: [{values: [{userEnteredValue: {stringValue: '내용'}}]}, {values: [{userEnteredValue: {stringValue: '점심'}}]}]}]};
  const calls = [];
  const locking = {acquired: 0, released: 0, available: true};
  const context = vm.createContext({
    PropertiesService: {getScriptProperties: () => ({getProperty: key => properties[key]})},
    ContentService: {MimeType: {JSON: 'application/json'}, createTextOutput: text => ({text, setMimeType() { return this; }})},
    Utilities: {DigestAlgorithm: {SHA_256: 'sha256'}, Charset: {UTF_8: 'utf8'},
      computeDigest: (_, text) => [...crypto.createHash('sha256').update(text).digest()],
      base64EncodeWebSafe: bytes => Buffer.from(bytes).toString('base64url')},
    LockService: {getScriptLock: () => ({tryLock() { locking.acquired++; return locking.available; }, releaseLock() { locking.released++; }})},
    Sheets: {Spreadsheets: {
      get: (id, options) => {
        calls.push({action: 'get', id, options});
        if (options.ranges) return {sheets: [copy(sheet)]};
        return {spreadsheetId: id, properties: {title: '테스트 장부'}, sheets: [{properties: copy(sheet.properties)}]};
      },
      Values: {get: () => ({values: [['식비'], ['교통']]})},
      batchUpdate: (body, id) => { calls.push({action: 'write', body: copy(body), id}); return {}; }
    }}
  });
  vm.runInContext(source, context);
  const send = overrides => JSON.parse(context.doPost({postData: {contents: JSON.stringify({key: KEY, spreadsheetId: ID, operation: 'book', ...overrides})}}).text);
  const revision = () => send({operation: 'grid', sheetId: 7}).data._relayRevision;
  const cellRequest = () => ({updateCells: {start: {sheetId: 7, rowIndex: 1, columnIndex: 0}, rows: [{values: [{userEnteredValue: {stringValue: '저녁'}}]}], fields: 'userEnteredValue'}});
  return {context, properties, sheet, calls, locking, send, revision, cellRequest};
}

test('public GET exposes health only', () => {
  const f = fixture();
  assert.deepEqual(JSON.parse(f.context.doGet().text), {ok: true, protocol: 1, data: {service: 'FamilyLedger relay'}});
  assert.equal(f.calls.length, 0);
});
test('missing, malformed and wrong keys never reach Sheets', () => {
  for (const key of ['', 'wrong', null, {}]) {
    const f = fixture();
    assert.equal(f.send({key}).status, 401);
    assert.equal(f.calls.length, 0);
  }
});
test('missing configuration fails closed', () => {
  const f = fixture(); delete f.properties.FAMILY_KEY;
  assert.equal(f.send({}).status, 503);
  assert.equal(f.calls.length, 0);
});
test('allowlist blocks other spreadsheets before any Google call', () => {
  const f = fixture();
  assert.equal(f.send({spreadsheetId: 'another_spreadsheet_id_123456'}).status, 403);
  assert.equal(f.calls.length, 0);
});
test('multiple allowed books and tab titles with apostrophes work', () => {
  const f = fixture();
  f.properties.ALLOWED_SPREADSHEET_IDS += ',second_spreadsheet_id_123456';
  assert.equal(f.send({spreadsheetId: 'second_spreadsheet_id_123456'}).ok, true);
  assert.equal(f.send({operation: 'grid', sheetId: 7}).ok, true);
  assert.equal(f.calls.at(-1).options.ranges[0], "'가족''s 장부'!A1:H20");
});
test('unknown operations and malformed JSON are rejected', () => {
  const f = fixture();
  assert.equal(f.send({operation: 'deleteSpreadsheet'}).status, 400);
  assert.equal(JSON.parse(f.context.doPost({postData: {contents: '{'}}).text).status, 400);
  assert.equal(f.calls.length, 0);
});
test('snapshot revision permits a checked atomic batch', () => {
  const f = fixture();
  assert.equal(f.send({operation: 'mutate', sheetId: 7, revision: f.revision(), requests: [f.cellRequest()]}).ok, true);
  assert.equal(f.calls.filter(c => c.action === 'write').length, 1);
  assert.equal(f.locking.released, 1);
});
test('a changed original blocks the write and releases the lock', () => {
  const f = fixture(), revision = f.revision();
  f.sheet.data[0].rowData[1].values[0].userEnteredValue.stringValue = '외부 수정';
  assert.equal(f.send({operation: 'mutate', sheetId: 7, revision, requests: [f.cellRequest()]}).status, 409);
  assert.equal(f.calls.filter(c => c.action === 'write').length, 0);
  assert.equal(f.locking.released, 1);
});
test('deleting tabs, crossing tabs, invalid rows and formula injection are rejected', () => {
  const invalid = [
    () => ({deleteSheet: {sheetId: 7}}),
    f => { const r = f.cellRequest(); r.updateCells.start.sheetId = 8; return r; },
    f => { const r = f.cellRequest(); r.updateCells.start.rowIndex = 20; return r; },
    f => { const r = f.cellRequest(); r.updateCells.rows[0].values[0].userEnteredValue = {formulaValue: '=IMPORTXML("x")'}; return r; }
  ];
  for (const request of invalid) {
    const f = fixture();
    assert.equal(f.send({operation: 'mutate', sheetId: 7, revision: f.revision(), requests: [request(f)]}).status, 400);
    assert.equal(f.calls.filter(c => c.action === 'write').length, 0);
    assert.equal(f.locking.released, 1);
  }
});
test('existing formula cells cannot be overwritten even with a fresh revision', () => {
  const f = fixture();
  f.sheet.data[0].rowData[1].values[0].userEnteredValue = {formulaValue: '=SUM(A1)'};
  assert.equal(f.send({operation: 'mutate', sheetId: 7, revision: f.revision(), requests: [f.cellRequest()]}).status, 409);
  assert.equal(f.calls.filter(c => c.action === 'write').length, 0);
});
test('incomplete grids and busy relay are not writable', () => {
  const f = fixture(); f.sheet.properties.gridProperties.rowCount = 10001;
  assert.equal(f.send({operation: 'mutate', sheetId: 7, revision: f.revision(), requests: [f.cellRequest()]}).status, 409);
  const g = fixture(); g.locking.available = false;
  assert.equal(g.send({operation: 'mutate', sheetId: 7, revision: g.revision(), requests: [g.cellRequest()]}).status, 409);
  assert.equal(g.locking.released, 0);
});
test('volatile formula recalculation does not invalidate unchanged editable cells', () => {
  const f = fixture();
  const cell = {userEnteredValue: {formulaValue: '=NOW()'}, effectiveValue: {numberValue: 1}};
  f.sheet.data[0].rowData[0].values.push(cell);
  const revision = f.revision();
  cell.effectiveValue.numberValue = 2;
  assert.equal(f.revision(), revision);
  assert.equal(f.send({operation: 'mutate', sheetId: 7, revision, requests: [f.cellRequest()]}).ok, true);
});
test('only up to two new identity columns can be appended and hidden', () => {
  const f = fixture();
  const requests = [{appendDimension: {sheetId: 7, dimension: 'COLUMNS', length: 2}},
    {updateDimensionProperties: {range: {sheetId: 7, dimension: 'COLUMNS', startIndex: 8, endIndex: 10}, properties: {hiddenByUser: true}, fields: 'hiddenByUser'}}];
  assert.equal(f.send({operation: 'mutate', sheetId: 7, revision: f.revision(), requests}).ok, true);
  requests[0].appendDimension.length = 3;
  assert.equal(f.send({operation: 'mutate', sheetId: 7, revision: f.revision(), requests}).status, 400);
});
test('keys and upstream exception details never appear in error responses', () => {
  const f = fixture();
  f.context.Sheets.Spreadsheets.get = () => { throw Error('sensitive sheet details: ' + KEY); };
  const result = f.send({});
  assert.equal(result.status, 500);
  assert.equal(JSON.stringify(result).includes(KEY), false);
  assert.equal(JSON.stringify(result).includes('sensitive sheet'), false);
});

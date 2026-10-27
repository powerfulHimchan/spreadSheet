/* FamilyLedger relay protocol 1. No sheet contents or connection keys are logged. */
var PROTOCOL_ = 1;
var BOOK_FIELDS_ = 'spreadsheetId,properties(title),sheets(properties(sheetId,title,gridProperties(rowCount,columnCount)))';
var GRID_FIELDS_ = 'sheets(properties(sheetId,title,gridProperties(rowCount,columnCount)),data(startRow,startColumn,rowData(values(userEnteredValue,effectiveValue,effectiveFormat(numberFormat),dataValidation))))';

function doGet() {
  return output_({ok: true, protocol: PROTOCOL_, data: {service: 'FamilyLedger relay'}});
}

function doPost(event) {
  try {
    var text = event && event.postData && event.postData.contents;
    ensure_(typeof text === 'string' && text.length <= 4 * 1024 * 1024, 400, '요청 크기와 형식을 확인해 주세요.');
    var input;
    try { input = JSON.parse(text); } catch (_) { fail_(400, 'JSON 요청 형식이 올바르지 않습니다.'); }
    ensure_(input && typeof input === 'object' && !Array.isArray(input), 400, '요청 형식이 올바르지 않습니다.');
    var properties = PropertiesService.getScriptProperties();
    var key = properties.getProperty('FAMILY_KEY') || '';
    ensure_(/^[A-Za-z0-9_-]{32,128}$/.test(key), 503, '소유자가 스크립트의 FAMILY_KEY 속성을 설정해야 합니다.');
    ensure_(typeof input.key === 'string' && input.key.length <= 128 && sameSecret_(input.key, key), 401, '가족 연결키가 맞지 않습니다. 앱과 스크립트 설정을 확인해 주세요.');
    var ids = allowedIds_(properties);
    ensure_(typeof input.spreadsheetId === 'string' && ids.indexOf(input.spreadsheetId) >= 0, 403, '허용된 스프레드시트가 아닙니다. ALLOWED_SPREADSHEET_IDS 설정을 확인해 주세요.');
    var data;
    switch (input.operation) {
      case 'book': data = book_(input.spreadsheetId); break;
      case 'grid': data = grid_(input.spreadsheetId, input.sheetId); break;
      case 'values':
        ensure_(typeof input.range === 'string' && input.range.length > 0 && input.range.length <= 1000, 400, '선택 목록 범위를 확인해 주세요.');
        data = Sheets.Spreadsheets.Values.get(input.spreadsheetId, input.range, {valueRenderOption: 'UNFORMATTED_VALUE'});
        break;
      case 'mutate': data = mutate_(input); break;
      default: fail_(400, '지원하지 않는 요청입니다. 앱과 중계 코드를 업데이트해 주세요.');
    }
    return output_({ok: true, protocol: PROTOCOL_, data: data});
  } catch (error) {
    // Google API exceptions may contain request details; never expose or log them.
    return output_({ok: false, protocol: PROTOCOL_, status: error.relayStatus || 500,
      message: error.relayStatus ? error.message : '중계 처리에 실패했습니다. 소유자의 시트 권한·Sheets 서비스·배포 상태를 확인해 주세요. 저장 중이었다면 새로고침해 반영 여부를 확인해 주세요.'});
  }
}

/** Run once in the editor to grant the owner access and verify the configured sheet IDs. */
function checkConfiguration() {
  var properties = PropertiesService.getScriptProperties();
  ensure_(/^[A-Za-z0-9_-]{32,128}$/.test(properties.getProperty('FAMILY_KEY') || ''), 503, 'FAMILY_KEY를 설정해 주세요.');
  var ids = allowedIds_(properties);
  ids.forEach(function(id) { book_(id); });
  return {configuredSpreadsheets: ids.length, protocol: PROTOCOL_};
}

function allowedIds_(properties) {
  var ids = (properties.getProperty('ALLOWED_SPREADSHEET_IDS') || '').split(/[\s,]+/).filter(Boolean);
  ensure_(ids.length > 0 && ids.length <= 100 && ids.every(function(id) { return /^[A-Za-z0-9_-]{20,}$/.test(id); }),
    503, 'ALLOWED_SPREADSHEET_IDS에 시트 URL의 /d/ 다음 ID를 쉼표로 구분해 입력해 주세요.');
  return ids;
}

function book_(id) {
  return Sheets.Spreadsheets.get(id, {fields: BOOK_FIELDS_});
}

function grid_(id, sheetId) {
  ensure_(Number.isInteger(sheetId) && sheetId >= 0, 400, '탭 ID가 올바르지 않습니다.');
  var sheets = book_(id).sheets || [];
  var sheet = sheets.find(function(s) { return s.properties.sheetId === sheetId && s.properties.gridProperties; });
  ensure_(sheet, 404, '연결한 탭을 찾을 수 없습니다.');
  var dimensions = sheet.properties.gridProperties;
  var range = "'" + sheet.properties.title.replace(/'/g, "''") + "'!A1:" + column_(Math.min(dimensions.columnCount, 200) - 1) + Math.min(dimensions.rowCount, 10000);
  var data = Sheets.Spreadsheets.get(id, {ranges: [range], fields: GRID_FIELDS_});
  data.sheets = (data.sheets || []).filter(function(s) { return s.properties.sheetId === sheetId; });
  ensure_(data.sheets.length === 1, 409, '탭 구성이 변경되었습니다. 새로고침해 주세요.');
  var versioned = JSON.parse(JSON.stringify(data));
  (versioned.sheets || []).forEach(function(s) { (s.data || []).forEach(function(block) {
    (block.rowData || []).forEach(function(row) { (row.values || []).forEach(function(cell) {
      // Volatile formula results can change between reads. Formulas themselves remain protected.
      if (cell.userEnteredValue && cell.userEnteredValue.formulaValue) delete cell.effectiveValue;
    }); });
  }); });
  data._relayRevision = digest_(canonical_(versioned));
  return data;
}

function mutate_(input) {
  ensure_(typeof input.revision === 'string' && /^[A-Za-z0-9_-]{43}$/.test(input.revision), 400, '최신 데이터를 불러온 후 저장해 주세요.');
  ensure_(Array.isArray(input.requests) && input.requests.length > 0 && input.requests.length <= 12002, 400, '한 번에 저장할 요청 수를 줄여 주세요.');
  var lock = LockService.getScriptLock();
  ensure_(lock.tryLock(25000), 409, '다른 가족의 저장을 처리하고 있습니다. 잠시 후 새로고침해 주세요.');
  try {
    var current = grid_(input.spreadsheetId, input.sheetId);
    ensure_(sameSecret_(current._relayRevision, input.revision), 409, '다른 곳에서 시트가 변경되었습니다. 새로고침한 후 다시 저장해 주세요.');
    var sheet = current.sheets.find(function(s) { return s.properties.sheetId === input.sheetId; });
    ensure_(sheet, 409, '탭 구성이 변경되었습니다. 새로고침해 주세요.');
    var dimensions = sheet.properties.gridProperties;
    ensure_(dimensions.rowCount <= 10000 && dimensions.columnCount <= 200, 409, '시트 일부만 읽혀 저장할 수 없습니다. 지원 범위는 10,000행·200열입니다.');
    validateRequests_(input.requests, input.sheetId, dimensions, sheet.data || []);
    Sheets.Spreadsheets.batchUpdate({requests: input.requests}, input.spreadsheetId);
    return {saved: true};
  } finally { lock.releaseLock(); }
}

function validateRequests_(requests, sheetId, dimensions, data) {
  var extra = 0;
  var appended = false;
  requests.forEach(function(request) {
    ensure_(request && typeof request === 'object' && Object.keys(request).length === 1, 400, '저장 요청 형식이 올바르지 않습니다.');
    if (request.appendDimension) {
      var append = request.appendDimension;
      ensure_(!appended && append.sheetId === sheetId && append.dimension === 'COLUMNS' && Number.isInteger(append.length) && append.length >= 1 && append.length <= 2,
        400, '식별 열 추가 요청이 올바르지 않습니다.');
      extra = append.length; appended = true;
      ensure_(dimensions.columnCount + extra <= 200, 400, '식별 열을 추가하면 지원 범위를 넘습니다.');
    } else if (request.updateDimensionProperties) {
      var update = request.updateDimensionProperties, range = update.range || {};
      ensure_(extra > 0 && range.sheetId === sheetId && range.dimension === 'COLUMNS' && range.startIndex === dimensions.columnCount &&
        range.endIndex === dimensions.columnCount + extra && update.fields === 'hiddenByUser' && update.properties &&
        Object.keys(update.properties).length === 1 && update.properties.hiddenByUser === true, 400, '새 식별 열만 숨길 수 있습니다.');
    } else if (request.updateCells) {
      var cells = request.updateCells, start = cells.start || {};
      ensure_(start.sheetId === sheetId && Number.isInteger(start.rowIndex) && start.rowIndex >= 0 && start.rowIndex < dimensions.rowCount &&
        Number.isInteger(start.columnIndex) && start.columnIndex >= 0 && start.columnIndex < dimensions.columnCount + extra &&
        !cells.range && (cells.fields === 'userEnteredValue' || cells.fields === 'userEnteredValue,userEnteredFormat.numberFormat') &&
        Array.isArray(cells.rows) && cells.rows.length === 1 && Array.isArray(cells.rows[0].values) && cells.rows[0].values.length === 1,
        400, '지정한 탭의 개별 셀만 변경할 수 있습니다.');
      var value = cells.rows[0].values[0];
      ensure_(value && typeof value === 'object' && Object.keys(value).every(function(k) { return k === 'userEnteredValue' || k === 'userEnteredFormat'; }), 400, '셀 변경 형식이 올바르지 않습니다.');
      if (value.userEnteredValue) {
        var entered = value.userEnteredValue, keys = Object.keys(entered);
        ensure_(keys.length === 1 && (keys[0] === 'stringValue' && typeof entered.stringValue === 'string' && entered.stringValue.length <= 50000 ||
          keys[0] === 'numberValue' && typeof entered.numberValue === 'number' && Number.isFinite(entered.numberValue) ||
          keys[0] === 'boolValue' && typeof entered.boolValue === 'boolean'), 400, '수식이나 지원하지 않는 값은 저장할 수 없습니다.');
      }
      if (value.userEnteredFormat) {
        var format = value.userEnteredFormat;
        ensure_(Object.keys(format).length === 1 && format.numberFormat && ['DATE', 'TIME', 'DATE_TIME'].indexOf(format.numberFormat.type) >= 0 &&
          typeof format.numberFormat.pattern === 'string' && format.numberFormat.pattern.length <= 100, 400, '날짜·시간 서식만 지정할 수 있습니다.');
      }
      ensure_(!cell_(data, start.rowIndex, start.columnIndex).formulaValue, 409, '수식 셀은 수정할 수 없습니다. 새로고침해 주세요.');
    } else { fail_(400, '행 삭제·탭 삭제·임의의 시트 작업은 지원하지 않습니다.'); }
  });
}

function cell_(blocks, row, column) {
  for (var i = 0; i < blocks.length; i++) {
    var block = blocks[i], r = row - (block.startRow || 0), c = column - (block.startColumn || 0);
    if (r >= 0 && c >= 0 && block.rowData && block.rowData[r]) {
      var values = block.rowData[r].values || [];
      return values[c] && values[c].userEnteredValue || {};
    }
  }
  return {};
}
function column_(index) {
  var result = '', n = index + 1;
  while (n > 0) { n--; result = String.fromCharCode(65 + n % 26) + result; n = Math.floor(n / 26); }
  return result;
}
function canonical_(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical_).join(',') + ']';
  if (value !== null && typeof value === 'object') return '{' + Object.keys(value).sort().map(function(key) { return JSON.stringify(key) + ':' + canonical_(value[key]); }).join(',') + '}';
  return JSON.stringify(value);
}
function digest_(text) { return Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, text, Utilities.Charset.UTF_8)).replace(/=+$/, ''); }
function sameSecret_(a, b) {
  var left = digest_(a), right = digest_(b), difference = 0;
  for (var i = 0; i < left.length; i++) difference |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return difference === 0;
}
function ensure_(condition, status, message) { if (!condition) fail_(status, message); }
function fail_(status, message) { var error = new Error(message); error.relayStatus = status; throw error; }
function output_(result) { return ContentService.createTextOutput(JSON.stringify(result)).setMimeType(ContentService.MimeType.JSON); }

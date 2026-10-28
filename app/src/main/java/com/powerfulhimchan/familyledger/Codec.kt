package com.powerfulhimchan.familyledger

import com.powerfulhimchan.familyledger.core.*
import org.json.JSONArray
import org.json.JSONObject

object Codec {
    fun strings(a: JSONArray?): List<String> = if(a==null) emptyList() else (0 until a.length()).map { a.getString(it) }
    fun mapping(m: TableMapping): String = JSONObject().apply {
        put("spreadsheetId",m.spreadsheetId);put("sheetId",m.sheetId);put("title",m.title);put("headerRow",m.headerRow);put("bookTitle",m.bookTitle)
        put("firstColumn",m.firstColumn);put("endColumn",m.endColumn);put("dataEndRow",m.dataEndRow);put("year",m.year)
        put("month",m.month ?: JSONObject.NULL);put("numberStyle",m.numberStyle.name)
        put("incomeValues",JSONArray(m.incomeValues.toList()));put("expenseValues",JSONArray(m.expenseValues.toList()))
        m.plan?.let { plan -> put("plan",JSONObject().apply {
            put("numberStyle",plan.numberStyle.name)
            put("regions",JSONArray(plan.regions.map { r -> JSONObject().apply {
                put("id",r.id);put("name",r.name);put("kind",r.kind.name);put("firstRow",r.firstRow);put("endRow",r.endRow)
                put("fields",JSONObject(r.fields.mapKeys { it.key.name }));put("carryTitle",r.carryTitle)
                put("anchors",JSONObject(r.anchors));put("excludedRows",JSONArray(r.excludedRows.toList()))
            } }))
        }) }
        put("columns",JSONArray(m.columns.map { c -> JSONObject().apply {
            put("key",c.key);put("header",c.header);put("index",c.index);put("type",c.type.name);put("role",c.role.name)
            put("readOnly",c.readOnly);put("options",JSONArray(c.options));put("labels",JSONObject(c.labels))
            put("strictOptions",c.strictOptions);put("required",c.required);put("dateMode",c.dateMode.name);put("enumNumeric",c.enumNumeric)
            put("validationRange",c.validationRange ?: JSONObject.NULL)
        }}))
    }.toString()
    fun mapping(raw: String): TableMapping {
        val o=JSONObject(raw);val cols=o.getJSONArray("columns")
        return TableMapping(o.getString("spreadsheetId"),o.getInt("sheetId"),o.getString("title"),o.getInt("headerRow"),o.getInt("firstColumn"),o.getInt("endColumn"),o.getInt("dataEndRow"),
            (0 until cols.length()).map { i -> val c=cols.getJSONObject(i);val labels=c.optJSONObject("labels") ?: JSONObject()
                Column(c.getString("key"),c.getString("header"),c.getInt("index"),FieldType.valueOf(c.getString("type")),Role.valueOf(c.getString("role")),c.optBoolean("readOnly"),strings(c.optJSONArray("options")),
                    labels.keys().asSequence().associateWith { labels.getString(it) },c.optBoolean("strictOptions"),c.optBoolean("required"),if(c.isNull("validationRange")) null else c.getString("validationRange"),DateMode.valueOf(c.optString("dateMode","FULL_DATE")),c.optBoolean("enumNumeric"))
            },o.getInt("year"),if(o.isNull("month")) null else o.getInt("month"),NumberStyle.valueOf(o.optString("numberStyle","DOT_DECIMAL")),strings(o.optJSONArray("expenseValues")).toSet(),strings(o.optJSONArray("incomeValues")).toSet(),o.optString("bookTitle"),o.optJSONObject("plan")?.let { plan ->
                val regions=plan.getJSONArray("regions")
                PlanMapping((0 until regions.length()).map { i -> val r=regions.getJSONObject(i);val fields=r.getJSONObject("fields");val anchors=r.optJSONObject("anchors") ?: JSONObject();val excluded=r.optJSONArray("excludedRows")
                    PlanRegion(r.getString("id"),r.getString("name"),PlanKind.valueOf(r.getString("kind")),r.getInt("firstRow"),r.getInt("endRow"),
                        fields.keys().asSequence().associate { PlanField.valueOf(it) to fields.getInt(it) },r.optBoolean("carryTitle"),
                        anchors.keys().asSequence().associateWith { anchors.getString(it) },if(excluded==null) emptySet() else (0 until excluded.length()).map { excluded.getInt(it) }.toSet())
                },NumberStyle.valueOf(plan.optString("numberStyle","DOT_DECIMAL")))
            })
    }
    fun cell(c: Cell): JSONObject = JSONObject().apply {
        put("value",c.value);put("kind",c.kind.name);put("format",c.format);put("pattern",c.pattern)
        put("formula",c.formula ?: JSONObject.NULL);put("options",JSONArray(c.options));put("checkbox",c.checkbox)
        put("validationRange",c.validationRange ?: JSONObject.NULL)
    }
    fun cell(o: JSONObject): Cell = Cell(o.optString("value"),ValueKind.valueOf(o.getString("kind")),o.optString("format"),o.optString("pattern"),if(o.isNull("formula")) null else o.getString("formula"),strings(o.optJSONArray("options")),if(o.isNull("validationRange")) null else o.getString("validationRange"),o.optBoolean("checkbox"))
    fun grid(g: Grid): String = JSONObject().apply {
        put("spreadsheetId",g.spreadsheetId);put("sheetId",g.sheetId);put("title",g.title);put("rowCount",g.rowCount);put("columnCount",g.columnCount);put("complete",g.complete);put("revision",g.revision ?: JSONObject.NULL)
        put("rows",JSONArray(g.rows.map { row -> JSONArray(row.map { cell(it) }) }))
    }.toString()
    fun grid(raw: String): Grid {
        val o=JSONObject(raw);val rows=o.getJSONArray("rows")
        return Grid(o.getString("spreadsheetId"),o.getInt("sheetId"),o.getString("title"),(0 until rows.length()).map { r -> val row=rows.getJSONArray(r);(0 until row.length()).map { c -> cell(row.getJSONObject(c)) } },o.getInt("rowCount"),o.getInt("columnCount"),o.optBoolean("complete",true),if(o.isNull("revision")) null else o.getString("revision"))
    }
}

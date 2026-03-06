package net.portswigger.mcp.mcp

internal fun String.camelToSnakeCase(): String {
    if (isEmpty()) return this
    val out = StringBuilder(length + 8)
    forEachIndexed { index, char ->
        if (char.isUpperCase() && index > 0) {
            out.append('_')
        }
        out.append(char.lowercaseChar())
    }
    return out.toString()
}

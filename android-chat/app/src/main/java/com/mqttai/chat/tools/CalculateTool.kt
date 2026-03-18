package com.mqttai.chat.tools

object CalculateTool {
    /**
     * Evaluates a math expression string and returns the numeric result.
     * Supports +, -, *, /, parentheses, and ^ (converted to Math.pow).
     */
    fun evaluate(expression: String): Double {
        // Simple recursive descent parser for safety (no script engine needed)
        val sanitized = expression
            .replace("^", "**")
            .replace(" ", "")
        return evalExpr(sanitized, IntArray(1) { 0 })
    }

    private fun evalExpr(s: String, pos: IntArray): Double {
        var result = evalTerm(s, pos)
        while (pos[0] < s.length) {
            val c = s[pos[0]]
            if (c == '+' || c == '-') {
                pos[0]++
                val term = evalTerm(s, pos)
                result = if (c == '+') result + term else result - term
            } else break
        }
        return result
    }

    private fun evalTerm(s: String, pos: IntArray): Double {
        var result = evalPower(s, pos)
        while (pos[0] < s.length) {
            val c = s[pos[0]]
            if (c == '*' && pos[0] + 1 < s.length && s[pos[0] + 1] != '*') {
                pos[0]++
                result *= evalPower(s, pos)
            } else if (c == '/') {
                pos[0]++
                result /= evalPower(s, pos)
            } else break
        }
        return result
    }

    private fun evalPower(s: String, pos: IntArray): Double {
        var base = evalUnary(s, pos)
        if (pos[0] + 1 < s.length && s[pos[0]] == '*' && s[pos[0] + 1] == '*') {
            pos[0] += 2
            val exp = evalPower(s, pos) // right-associative
            base = Math.pow(base, exp)
        }
        return base
    }

    private fun evalUnary(s: String, pos: IntArray): Double {
        if (pos[0] < s.length && s[pos[0]] == '-') {
            pos[0]++
            return -evalAtom(s, pos)
        }
        return evalAtom(s, pos)
    }

    private fun evalAtom(s: String, pos: IntArray): Double {
        if (pos[0] < s.length && s[pos[0]] == '(') {
            pos[0]++ // skip '('
            val result = evalExpr(s, pos)
            if (pos[0] < s.length && s[pos[0]] == ')') pos[0]++ // skip ')'
            return result
        }
        val start = pos[0]
        while (pos[0] < s.length && (s[pos[0]].isDigit() || s[pos[0]] == '.')) {
            pos[0]++
        }
        return s.substring(start, pos[0]).toDouble()
    }
}

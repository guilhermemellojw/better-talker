package com.bettertalker.app.data.util

/**
 * Flexão mínima pt-BR: devolve [one] para 1 e [many] para os demais
 * (inclui 0: "0 notas"). Puro/testável.
 */
fun plural(n: Int, one: String, many: String): String = if (n == 1) one else many

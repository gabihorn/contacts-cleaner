package app.contactscleaner.ui

/** "מספר אחד" for one, "3 מספרים" for more: Hebrew doesn't say "1 מספרים". */
fun count(n: Int, one: String, many: String) = if (n == 1) one else "$n $many"

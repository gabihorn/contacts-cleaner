package app.contactscleaner.ui

import app.contactscleaner.core.Issue

/** How issues are grouped on the home screen. */
enum class Category(
    val title: String,
    val description: String,
    /** False for categories shown only for review, with nothing to apply. */
    val fixable: Boolean = true,
) {
    DUPLICATES("אנשי קשר כפולים", "אותו אדם שמור יותר מפעם אחת. האיחוד לא מוחק דבר, ואפשר לפצל בחזרה מאפליקציית אנשי הקשר."),
    PHONE_FORMAT("קידומת ופורמט", "מספרים שיעברו לפורמט בינלאומי אחיד, למשל 050-1234567 ← ‎+972501234567"),
    REPEATED("מספר שמור פעמיים", "אותו מספר או מייל מופיע כמה פעמים באותו איש קשר. העותקים המיותרים יימחקו."),
    MISSING_NAME("חסר שם", "אנשי קשר בלי שם, או שהשם הוא בעצם מספר. אפשר להקליד שם."),
    NAME_CLEANUP("סידור שמות", "רווחים מיותרים ושמות באנגלית בלי אותיות גדולות במקום."),
    EMPTY("אנשי קשר ריקים", "בלי מספר ובלי מייל. סימון ותיקון ימחקו אותם."),
    INVALID("מספרים לא תקינים", "מספרים שלא נראים תקינים. כדאי לבדוק ידנית.", fixable = false),
    ;

    companion object {
        fun of(issue: Issue): Category = when (issue) {
            is Issue.Duplicates -> DUPLICATES
            is Issue.PhoneFormat -> PHONE_FORMAT
            is Issue.RepeatedEntries -> REPEATED
            is Issue.MissingName -> MISSING_NAME
            is Issue.NameCleanup -> NAME_CLEANUP
            is Issue.EmptyContact -> EMPTY
            is Issue.InvalidPhone -> INVALID
        }
    }
}

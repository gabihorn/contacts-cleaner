package app.contactscleaner.ui

import app.contactscleaner.core.Issue

/** How issues are grouped on the home screen. */
enum class Category(
    val title: String,
    val description: String,
    /** False for categories with no fix to apply (only, maybe, deleting). */
    val fixable: Boolean = true,
    /** True where each item is a phone number the user may delete instead of fixing. */
    val deletesNumbers: Boolean = false,
    /** True where each item is a contact the user may delete. */
    val deletesContacts: Boolean = false,
    /** True where contacts can be moved to the app's archive instead of deleted. */
    val archives: Boolean = false,
) {
    DUPLICATES("אנשי קשר כפולים", "אותו אדם שמור יותר מפעם אחת. כל המידע יאוחד לאיש קשר אחד בחשבון גוגל, והעותקים יימחקו. אפשר לבטל מיומן השינויים."),
    PHONE_FORMAT("קידומת ופורמט", "מספרים שיעברו לפורמט בינלאומי אחיד, למשל 050-1234567 ← ‎+972501234567", deletesNumbers = true),
    MISSING_AREA_CODE("טלפון קווי בלי קידומת", "מספרים של 7 ספרות בלי קידומת אזור. בחר לכל מספר את הקידומת הנכונה, או מחק את המספרים.", deletesNumbers = true),
    PHONE_LABEL("סוג מספר", "נייד שמסומן \"בית\" או \"פקס\", או קווי שמסומן \"נייד\". התיקון משנה רק את התווית."),
    REPEATED("מספר שמור פעמיים", "אותו מספר או מייל מופיע כמה פעמים באותו איש קשר. העותקים המיותרים יימחקו."),
    MISSING_NAME("חסר שם", "אנשי קשר בלי שם, או שהשם הוא בעצם מספר. אפשר להקליד שם."),
    SUSPICIOUS_NAME(
        "שמות חשודים",
        "שם של אות אחת, תיאור במקום שם (\"שליח גז\", \"ההוא מהאירוע\") או אימוג'ים. אפשר לתקן את השם, להעביר לארכיון או למחוק.",
        deletesContacts = true,
        archives = true,
    ),
    NAME_CLEANUP("סידור שמות", "נקודות ופסיקים מיותרים בתחילת השם או בסופו, רווחים כפולים, ושמות באנגלית בלי אותיות גדולות במקום."),
    EMPTY(
        "אנשי קשר ריקים",
        "בלי מספר ובלי מייל. ייתכן שיש בהם הערה או כתובת, אז כדאי להעביר לארכיון במקום למחוק.",
        fixable = false,
        deletesContacts = true,
        archives = true,
    ),
    OUTDATED("מספרים ישנים / לא פעילים", "מספרים בפורמט שכבר לא קיים (6 ספרות אחרי הקידומת), או בטווח שאף חברה לא קיבלה. כנראה לא יענו.", fixable = false, deletesNumbers = true),
    INACTIVE(
        "ניקוי לפי פעילות",
        "סינון לפי וואטסאפ ושיחה אחרונה. ברירת המחדל: יש להם וואטסאפ אבל אף פעם לא דיברתם בטלפון, הפרופיל של מי שנשמר בשביל הסטטוס. שיחות והודעות וואטסאפ לא נספרות, כי וואטסאפ לא חושף אותן.",
        fixable = false,
        deletesContacts = true,
        archives = true,
    ),
    INVALID("מספרים לא תקינים", "מספרים שלא נראים תקינים. כדאי לבדוק ידנית או למחוק.", fixable = false, deletesNumbers = true),
    ;

    companion object {
        fun of(issue: Issue): Category = when (issue) {
            is Issue.Duplicates -> DUPLICATES
            is Issue.PhoneFormat -> PHONE_FORMAT
            is Issue.PhoneLabel -> PHONE_LABEL
            is Issue.SuspiciousName -> SUSPICIOUS_NAME
            is Issue.MissingAreaCode -> MISSING_AREA_CODE
            is Issue.RepeatedEntries -> REPEATED
            is Issue.MissingName -> MISSING_NAME
            is Issue.NameCleanup -> NAME_CLEANUP
            is Issue.EmptyContact -> EMPTY
            is Issue.Inactive -> INACTIVE
            is Issue.OutdatedPhone -> OUTDATED
            is Issue.InvalidPhone -> INVALID
        }
    }
}

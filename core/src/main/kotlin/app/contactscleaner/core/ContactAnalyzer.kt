package app.contactscleaner.core

/** Scans an address book and lists everything that could be fixed. Changes nothing. */
class ContactAnalyzer(
    private val normalizer: PhoneNormalizer = PhoneNormalizer(),
) {
    /**
     * [withActivity] adds an [Issue.Inactive] per contact with a phone number,
     * for when WhatsApp presence and the call log were read.
     */
    fun analyze(contacts: List<Contact>, withActivity: Boolean = false): AnalysisReport {
        val issues = mutableListOf<Issue>()
        val commonAreaCode = contacts.asSequence()
            .flatMap { it.phones }
            .mapNotNull { normalizer.israeliAreaCode(it.number) }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        for (contact in contacts) {
            issues += perContactIssues(contact, commonAreaCode)
        }
        issues += findDuplicates(contacts)
        if (withActivity) {
            issues += contacts.filter { it.phones.isNotEmpty() }.map { Issue.Inactive(it, it.onWhatsApp, it.lastCall) }
        }
        return AnalysisReport(contacts.size, issues)
    }

    private fun perContactIssues(contact: Contact, commonAreaCode: String?): List<Issue> {
        val issues = mutableListOf<Issue>()

        // Repeated phones: keep the copy already in canonical form if there is one.
        val repeatedPhones = mutableListOf<PhoneEntry>()
        contact.phones.groupBy { normalizer.key(it.number) }.values
            .filter { it.size > 1 }
            .forEach { same ->
                val keep = same.firstOrNull { canonical(it.number) == it.number } ?: same.first()
                repeatedPhones += same - keep
            }
        val repeatedEmails = contact.emails.groupBy { it.address.trim().lowercase() }.values
            .filter { it.size > 1 }
            .flatMap { it.drop(1) }
        if (repeatedPhones.isNotEmpty() || repeatedEmails.isNotEmpty()) {
            issues += Issue.RepeatedEntries(contact, repeatedPhones, repeatedEmails)
        }

        for (phone in contact.phones) {
            if (phone in repeatedPhones) continue
            when (val r = normalizer.normalize(phone.number)) {
                is PhoneResult.Valid -> {
                    if (r.formatted != phone.number) issues += Issue.PhoneFormat(contact, phone, r.formatted)
                    if (!r.allocated) issues += Issue.OutdatedPhone(contact, phone, OutdatedReason.UNALLOCATED)
                }
                is PhoneResult.Invalid -> issues += if (normalizer.lacksIsraeliAreaCode(phone.number)) {
                    val ownAreaCode = contact.phones.firstNotNullOfOrNull { normalizer.israeliAreaCode(it.number) }
                    Issue.MissingAreaCode(contact, phone, ownAreaCode ?: commonAreaCode)
                } else {
                    normalizer.outdatedReason(phone.number)?.let { Issue.OutdatedPhone(contact, phone, it) }
                        ?: Issue.InvalidPhone(contact, phone)
                }
                is PhoneResult.Special -> Unit
            }
        }

        val name = contact.name
        val displayName = contact.displayName
        if (displayName == null || Names.looksLikePhoneOrEmail(displayName)) {
            val guess = contact.meName ?: contact.emails.firstNotNullOfOrNull { Names.fromEmail(it.address) }
            issues += Issue.MissingName(contact, guess)
        } else if (name != null) {
            Names.cleanup(displayName)?.let { issues += Issue.NameCleanup(contact, name, it) }
        }

        if (contact.phones.isEmpty() && contact.emails.isEmpty()) {
            issues += Issue.EmptyContact(contact)
        }
        return issues
    }

    private fun canonical(number: String): String? =
        (normalizer.normalize(number) as? PhoneResult.Valid)?.formatted

    private fun findDuplicates(contacts: List<Contact>): List<Issue.Duplicates> {
        val parent = IntArray(contacts.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        val links = mutableListOf<Triple<Int, Int, DuplicateReason>>()
        fun linkAll(reason: DuplicateReason, keysOf: (Contact) -> Collection<String>) {
            val firstSeen = HashMap<String, Int>()
            contacts.forEachIndexed { i, c ->
                for (key in keysOf(c).toSet()) {
                    val j = firstSeen.putIfAbsent(key, i) ?: continue
                    if (j == i) continue
                    links += Triple(j, i, reason)
                    parent[find(i)] = find(j)
                }
            }
        }

        // Only real numbers link contacts; a shared short code like *2700 means nothing.
        linkAll(DuplicateReason.SAME_PHONE) { c ->
            c.phones.mapNotNull { (normalizer.normalize(it.number) as? PhoneResult.Valid)?.key }
        }
        linkAll(DuplicateReason.SAME_EMAIL) { c ->
            c.emails.map { it.address.trim().lowercase() }.filter { '@' in it }
        }
        linkAll(DuplicateReason.SAME_NAME) { c ->
            listOfNotNull(c.displayName?.takeUnless(Names::looksLikePhoneOrEmail)?.let(Names::matchKey))
        }

        val reasonsByGroup = HashMap<Int, MutableSet<DuplicateReason>>()
        for ((a, _, reason) in links) {
            reasonsByGroup.getOrPut(find(a)) { mutableSetOf() } += reason
        }

        return contacts.indices.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { members ->
                val group = members.map { contacts[it] }
                val reasons = reasonsByGroup.getValue(find(members.first()))
                val strong = DuplicateReason.SAME_PHONE in reasons || DuplicateReason.SAME_EMAIL in reasons
                Issue.Duplicates(group, reasons, mergePreview(group), strong && namesCompatible(group))
            }
    }

    /**
     * Names don't contradict: missing, equal, or one is part of the other
     * ("Moshe" / "Moshe Cohen"). "Moshe Cohen" vs "Rachel Cohen" is not.
     */
    private fun namesCompatible(group: List<Contact>): Boolean {
        val tokenSets = group
            .mapNotNull { it.displayName?.takeUnless(Names::looksLikePhoneOrEmail) }
            .mapNotNull { Names.matchKey(it)?.split(' ')?.toSet() }
        return tokenSets.all { a -> tokenSets.all { b -> a.containsAll(b) || b.containsAll(a) } }
    }

    fun mergePreview(group: List<Contact>): MergePreview {
        val name = group.mapNotNull { it.displayName }
            .filterNot(Names::looksLikePhoneOrEmail)
            .maxByOrNull { it.trim().length }
            ?.let { Names.cleanup(it) ?: it }

        val phones = LinkedHashMap<String, String>()
        for (phone in group.flatMap { it.phones }) {
            val key = normalizer.key(phone.number)
            if (key !in phones) phones[key] = canonical(phone.number) ?: phone.number.trim()
        }

        val emails = LinkedHashMap<String, String>()
        for (email in group.flatMap { it.emails }) {
            emails.putIfAbsent(email.address.trim().lowercase(), email.address.trim())
        }

        return MergePreview(
            name = name,
            phones = phones.values.toList(),
            emails = emails.values.toList(),
            organization = group.firstNotNullOfOrNull { it.organization?.takeIf(String::isNotBlank) },
        )
    }
}

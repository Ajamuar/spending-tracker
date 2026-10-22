package com.ajamuar.ledger

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

fun members(group: PrivateGroup): List<String> = JSONArray(group.members).let { a -> (0 until a.length()).map(a::getString) }
fun shares(split: Split): Map<String, Long> = JSONObject(split.shares).let { o -> o.keys().asSequence().associateWith(o::getLong) }

fun validateBill(bill: Bill) {
    UUID.fromString(bill.id)
    require(bill.name.isNotBlank() && bill.name.length <= 120)
    LocalDate.parse(bill.due)
    require(bill.amount == null || bill.amount in 1..Money.MAX_MINOR)
    require(bill.repeat in listOf("None", "Monthly", "Yearly"))
}

suspend fun Repository.saveBill(bill: Bill) {
    validateBill(bill)
    bill.paymentId?.let { id ->
        val entry = requireNotNull(dao.entry(id)) { "Payment no longer exists" }
        require(bill.paid && !entry.deleted && entry.status == Status.POSTED.name && entry.kind in listOf(Kind.EXPENSE.name, Kind.CARD_PAYMENT.name, Kind.TRANSFER.name)) { "Choose a posted outgoing payment" }
        require(bill.amount == null || entry.amount >= bill.amount) { "Payment is less than the bill amount" }
    }
    dao.putBill(bill)
}

fun validateGroup(group: PrivateGroup) {
    UUID.fromString(group.id)
    val people = members(group)
    require(group.name.isNotBlank() && group.name.length <= 100)
    require(people.size in 2..50 && people.first() == "You" && people.all { it.isNotBlank() && it.length <= 60 } && people.map { it.lowercase() }.distinct().size == people.size) { "Use unique member names; You is the first member" }
}

fun validateSplit(split: Split, group: PrivateGroup) {
    UUID.fromString(split.id)
    require(split.amount in 1..Money.MAX_MINOR && split.description.isNotBlank() && split.description.length <= 120)
    val people = members(group)
    require(split.payer in people)
    if (split.settlementTo != null) require(split.settlementTo in people && split.settlementTo != split.payer && shares(split).isEmpty())
    else {
        val allocation = shares(split)
        require(allocation.keys == people.toSet() && allocation.values.all { it in 0..split.amount } && allocation.values.fold(0L, Math::addExact) == split.amount) { "Shares must add up exactly to the expense" }
    }
}

suspend fun Repository.saveSplit(split: Split, chosenAccount: String) {
    db.withTransaction {
        val group = requireNotNull(dao.group(split.groupId))
        validateSplit(split, group)
        require(dao.split(split.id) == null) { "This group entry already exists" }
        if (split.settlementTo != null) { dao.putSplit(split); return@withTransaction }
        val own = shares(split).getValue("You")
        var linked = split.entryId?.let { requireNotNull(dao.entry(it)) }
        val generated = linked == null
        if (linked != null) {
            require(split.payer == "You" && linked.amount == split.amount && linked.kind == Kind.EXPENSE.name && !linked.deleted && dao.entrySplit(linked.id) == null) { "Choose an unlinked expense paid by you with the same amount" }
            linked = linked.copy(personalMinor = own)
        } else if (split.payer == "You") {
            linked = Entry(amount = split.amount, merchant = split.description, accountId = chosenAccount, personalMinor = own)
        } else if (own > 0) {
            dao.insertAccount(Account("group-payable", "Group expenses paid by others", "Other", "group-payable"))
            linked = Entry(amount = own, merchant = split.description, accountId = "group-payable", personalMinor = own)
        }
        linked?.let { save(it) }
        dao.putSplit(split.copy(entryId = linked?.id, generatedEntry = generated))
    }
}

suspend fun Repository.removeSplit(split: Split) {
    db.withTransaction {
        dao.deleteSplit(split)
        split.entryId?.let { id -> dao.entry(id)?.let { entry ->
            save(if (split.generatedEntry) entry.copy(deleted = true) else entry.copy(personalMinor = null))
        } }
    }
}

suspend fun Repository.mergeAccounts(source: String, target: String) {
    require(source != target) { "Choose two different accounts" }
    db.withTransaction {
        val from = requireNotNull(dao.account(source)); val to = requireNotNull(dao.account(target))
        require(!to.archived && from.type == to.type) { "Choose an active destination of the same account type" }
        dao.snapshot().filter { it.accountId == source }.forEach { save(it.copy(accountId = target)) }
        dao.putAccount(from.copy(archived = true))
        dao.putMetadata(Metadata("account-alias:$source", target))
    }
}

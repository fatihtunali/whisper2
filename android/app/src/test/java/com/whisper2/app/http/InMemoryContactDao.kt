package com.whisper2.app.http

import com.whisper2.app.storage.db.dao.ContactDao
import com.whisper2.app.storage.db.entities.ContactEntity

/**
 * In-memory ContactDao implementation for testing
 */
class InMemoryContactDao : ContactDao {

    private val contacts = mutableMapOf<String, ContactEntity>()

    override fun insert(contact: ContactEntity): Long {
        contacts[contact.id] = contact
        return contacts.size.toLong()
    }

    override fun insertAll(newContacts: List<ContactEntity>) {
        newContacts.forEach { contacts[it.id] = it }
    }

    override fun update(contact: ContactEntity) {
        if (contacts.containsKey(contact.id)) {
            contacts[contact.id] = contact
        }
    }

    override fun getById(id: String): ContactEntity? {
        return contacts[id]
    }

    override fun getByWhisperId(whisperId: String): ContactEntity? {
        return contacts.values.find { it.whisperId == whisperId }
    }

    override fun getAll(): List<ContactEntity> {
        return contacts.values.toList().sortedWith(
            compareBy({ it.displayName }, { it.whisperId })
        )
    }

    override fun getAllNonBlocked(): List<ContactEntity> {
        return contacts.values
            .filter { !it.isBlocked }
            .sortedWith(compareBy({ it.displayName }, { it.whisperId }))
    }

    override fun getFavorites(): List<ContactEntity> {
        return contacts.values
            .filter { it.isFavorite }
            .sortedWith(compareBy({ it.displayName }, { it.whisperId }))
    }

    override fun exists(whisperId: String): Boolean {
        return contacts.values.any { it.whisperId == whisperId }
    }

    override fun count(): Int {
        return contacts.size
    }

    override fun delete(id: String) {
        contacts.remove(id)
    }

    override fun deleteByWhisperId(whisperId: String) {
        val toRemove = contacts.values.find { it.whisperId == whisperId }
        if (toRemove != null) {
            contacts.remove(toRemove.id)
        }
    }

    override fun deleteAll() {
        contacts.clear()
    }

    override fun replaceAll(newContacts: List<ContactEntity>) {
        deleteAll()
        insertAll(newContacts)
    }

    fun clear() {
        contacts.clear()
    }
}

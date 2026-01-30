import SwiftUI

/// Contact list view
struct ContactsListView: View {
    @StateObject private var viewModel = ContactsViewModel()
    @State private var showAddContact = false
    @State private var searchText = ""
    @State private var showDeleteConfirmation = false
    @State private var contactToDelete: Contact?
    @State private var showBlockConfirmation = false
    @State private var contactToBlock: Contact?

    private var filteredContacts: [Contact] {
        if searchText.isEmpty {
            return viewModel.contacts
        }
        return viewModel.contacts.filter {
            $0.displayName.localizedCaseInsensitiveContains(searchText) ||
            $0.whisperId.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if viewModel.contacts.isEmpty {
                    EmptyContactsView(showAddContact: $showAddContact)
                } else {
                    List {
                        ForEach(filteredContacts) { contact in
                            ContactRow(contact: contact)
                                .listRowBackground(Color.black)
                                .listRowSeparatorTint(Color.gray.opacity(0.3))
                                // Swipe left: Delete contact
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    Button(role: .destructive) {
                                        contactToDelete = contact
                                        showDeleteConfirmation = true
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                }
                                // Swipe right: Block/Unblock
                                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                                    if contact.isBlocked {
                                        Button {
                                            viewModel.unblockContact(contact.whisperId)
                                            // Haptic feedback
                                            let notification = UINotificationFeedbackGenerator()
                                            notification.notificationOccurred(.success)
                                        } label: {
                                            Label("Unblock", systemImage: "hand.raised.slash")
                                        }
                                        .tint(.green)
                                    } else {
                                        Button {
                                            contactToBlock = contact
                                            showBlockConfirmation = true
                                        } label: {
                                            Label("Block", systemImage: "hand.raised")
                                        }
                                        .tint(.red)
                                    }
                                }
                        }
                    }
                    .listStyle(.plain)
                    .searchable(text: $searchText, prompt: "Search contacts")
                }
            }
            .navigationTitle("Contacts")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showAddContact = true }) {
                        Image(systemName: "person.badge.plus")
                    }
                }
            }
            .sheet(isPresented: $showAddContact) {
                AddContactView(viewModel: viewModel)
            }
            .alert("Delete Contact", isPresented: $showDeleteConfirmation) {
                Button("Cancel", role: .cancel) {
                    contactToDelete = nil
                }
                Button("Delete", role: .destructive) {
                    if let contact = contactToDelete {
                        viewModel.deleteContact(contact)
                    }
                    contactToDelete = nil
                }
            } message: {
                if let contact = contactToDelete {
                    Text("Are you sure you want to delete \(contact.displayName)? All messages with this contact will also be deleted.")
                }
            }
            .alert("Block Contact", isPresented: $showBlockConfirmation) {
                Button("Cancel", role: .cancel) {
                    contactToBlock = nil
                }
                Button("Block", role: .destructive) {
                    if let contact = contactToBlock {
                        viewModel.blockContact(contact.whisperId)
                        // Haptic feedback
                        let notification = UINotificationFeedbackGenerator()
                        notification.notificationOccurred(.warning)
                    }
                    contactToBlock = nil
                }
            } message: {
                if let contact = contactToBlock {
                    Text("Block \(contact.displayName)? They won't be able to send you messages or calls.")
                }
            }
        }
    }
}

/// Empty contacts view
struct EmptyContactsView: View {
    @Binding var showAddContact: Bool
    
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "person.2")
                .font(.system(size: 60))
                .foregroundColor(.gray)
            
            Text("No Contacts")
                .font(.title2)
                .fontWeight(.semibold)
                .foregroundColor(.white)
            
            Text("Add contacts using their Whisper ID")
                .font(.subheadline)
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
            
            Button(action: { showAddContact = true }) {
                Text("Add Contact")
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 32)
                    .padding(.vertical, 12)
                    .background(Color.blue)
                    .cornerRadius(25)
            }
            .padding(.top, 10)
        }
        .padding()
    }
}

#Preview {
    ContactsListView()
}

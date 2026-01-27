import SwiftUI

/// Contact list view
struct ContactsListView: View {
    @StateObject private var viewModel = ContactsViewModel()
    @State private var showAddContact = false
    @State private var searchText = ""
    
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
                        }
                        .onDelete(perform: deleteContact)
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
        }
    }
    
    private func deleteContact(at offsets: IndexSet) {
        viewModel.deleteContacts(at: offsets)
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

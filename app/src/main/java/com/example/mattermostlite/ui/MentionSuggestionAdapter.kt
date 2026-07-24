package com.example.mattermostlite.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.mattermostlite.R
import com.example.mattermostlite.model.MattermostUser

class MentionSuggestionAdapter(
    private val context: Context,
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private var users: List<MattermostUser> = emptyList()

    fun submit(newUsers: List<MattermostUser>) {
        if (users == newUsers) return
        users = newUsers
        notifyDataSetChanged()
    }

    override fun getCount(): Int = users.size

    override fun getItem(position: Int): MattermostUser = users[position]

    override fun getItemId(position: Int): Long = users[position].id.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_mention_suggestion, parent, false)
        val user = getItem(position)
        view.findViewById<TextView>(R.id.mentionSuggestionUsername).text = "@${user.username}"
        view.findViewById<TextView>(R.id.mentionSuggestionDisplayName).apply {
            text = user.displayName
            isVisible = !user.displayName.equals(user.username, ignoreCase = true)
        }
        view.contentDescription = context.getString(
            R.string.mention_user_suggestion,
            user.username,
            user.displayName,
        )
        return view
    }
}

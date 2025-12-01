package com.example.handycam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment

class AvcFragment : Fragment() {
    private var bitrateEdit: EditText? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_avc, container, false)
        bitrateEdit = v.findViewById(R.id.avcBitrateEdit)
        return v
    }

    fun getBitrate(): Int? {
        val text = bitrateEdit?.text?.toString() ?: ""
        return text.toIntOrNull()
    }
}

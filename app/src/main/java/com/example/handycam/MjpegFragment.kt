package com.example.handycam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment

class MjpegFragment : Fragment() {
    private var jpegEdit: EditText? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_mjpeg, container, false)
        jpegEdit = v.findViewById(R.id.jpegEdit)
        return v
    }

    fun getJpegQuality(): Int {
        val text = jpegEdit?.text?.toString() ?: ""
        return text.toIntOrNull() ?: 85
    }
}

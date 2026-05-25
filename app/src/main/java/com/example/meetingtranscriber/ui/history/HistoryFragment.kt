package com.example.meetingtranscriber.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager

import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.ui.detail.DetailFragment
import com.example.meetingtranscriber.databinding.FragmentHistoryBinding
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = HistoryAdapter(
            onItemClick = { meeting ->
                // 跳转到会议详情
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, DetailFragment.newInstance(meeting.id))
                    .addToBackStack(null)
                    .commit()
            },
            onDeleteClick = { meeting ->
                viewModel.deleteMeeting(meeting.id)
            }
        )

        binding.rvHistory.adapter = adapter
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.meetings.collect { meetings ->
                adapter.submitList(meetings)
                binding.tvEmpty.visibility = if (meetings.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

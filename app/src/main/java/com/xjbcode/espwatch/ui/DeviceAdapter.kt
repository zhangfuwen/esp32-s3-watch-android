package com.xjbcode.espwatch.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xjbcode.espwatch.R
import com.xjbcode.espwatch.data.DeviceInfo

class DeviceAdapter(
    private val onDeviceClick: (DeviceInfo) -> Unit
) : ListAdapter<DeviceInfo, DeviceAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view, onDeviceClick)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DeviceViewHolder(
        itemView: View,
        private val onDeviceClick: (DeviceInfo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvDeviceAddress)
        private val tvSignal: TextView = itemView.findViewById(R.id.tvSignalStrength)

        fun bind(device: DeviceInfo) {
            tvName.text = device.displayName
            tvAddress.text = device.address
            tvSignal.text = "${device.rssi} dBm"
            
            val signalColor = when (device.signalStrength) {
                DeviceInfo.SignalStrength.EXCELLENT -> android.R.color.holo_green_dark
                DeviceInfo.SignalStrength.GOOD -> android.R.color.holo_green_light
                DeviceInfo.SignalStrength.FAIR -> android.R.color.holo_orange_light
                DeviceInfo.SignalStrength.WEAK -> android.R.color.holo_red_light
            }
            tvSignal.setTextColor(itemView.context.getColor(signalColor))
            
            itemView.setOnClickListener { onDeviceClick(device) }
        }
    }

    class DeviceDiffCallback : DiffUtil.ItemCallback<DeviceInfo>() {
        override fun areItemsTheSame(oldItem: DeviceInfo, newItem: DeviceInfo): Boolean {
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: DeviceInfo, newItem: DeviceInfo): Boolean {
            return oldItem == newItem
        }
    }
}

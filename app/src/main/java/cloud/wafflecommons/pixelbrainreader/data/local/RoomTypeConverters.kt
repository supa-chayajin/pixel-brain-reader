package cloud.wafflecommons.pixelbrainreader.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID

class RoomTypeConverters {

    private val gson = Gson()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val timeFormatter = DateTimeFormatter.ISO_LOCAL_TIME

    // --- List<String> ---
    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        if (value == null) return "[]"
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return try {
            gson.fromJson(value, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- LocalDate ---
    @TypeConverter
    fun fromLocalDate(date: LocalDate?): String? {
        return date?.format(dateFormatter)
    }

    @TypeConverter
    fun toLocalDate(string: String?): LocalDate? {
        return try {
            string?.let { LocalDate.parse(it, dateFormatter) }
        } catch (e: Exception) {
            null
        }
    }

    // --- LocalDateTime ---
    @TypeConverter
    fun fromLocalDateTime(dateTime: LocalDateTime?): String? {
        return dateTime?.format(dateTimeFormatter)
    }

    @TypeConverter
    fun toLocalDateTime(string: String?): LocalDateTime? {
        return try {
            string?.let { LocalDateTime.parse(it, dateTimeFormatter) }
        } catch (e: Exception) {
            null
        }
    }

    // --- LocalTime ---
    @TypeConverter
    fun fromLocalTime(time: java.time.LocalTime?): String? {
        return time?.format(timeFormatter)
    }

    @TypeConverter
    fun toLocalTime(string: String?): java.time.LocalTime? {
        return try {
            string?.let { java.time.LocalTime.parse(it, timeFormatter) }
        } catch (e: Exception) {
            null
        }
    }

    // --- Date ---
    @TypeConverter
    fun fromDate(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun toDate(timestamp: Long?): Date? {
        return timestamp?.let { Date(it) }
    }

    // --- UUID ---
    @TypeConverter
    fun fromUUID(uuid: UUID?): String? {
        return uuid?.toString()
    }

    @TypeConverter
    fun toUUID(string: String?): UUID? {
        return try {
            string?.let { UUID.fromString(it) }
        } catch (e: Exception) {
            null
        }
    }

    // --- Float List ---
    @TypeConverter
    fun fromFloatList(value: List<Float>?): String? {
        if (value == null) return null
        return value.joinToString(",")
    }

    @TypeConverter
    fun toFloatList(value: String?): List<Float>? {
        if (value.isNullOrEmpty()) return null
        return value.split(",").mapNotNull { it.toFloatOrNull() }
    }
}

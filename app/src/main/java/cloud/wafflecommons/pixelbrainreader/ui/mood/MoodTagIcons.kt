package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Commute
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Forest
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NaturePeople
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.OutdoorGrill
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Piano
import androidx.compose.material.icons.outlined.Pool
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SoupKitchen
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.SportsBasketball
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SportsMartialArts
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.SportsTennis
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.WorkHistory
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag

/** One icon a mood tag can wear. [key] is saved in the vault's `mood_tags.json`: never rename one. */
class MoodTagIcon(val key: String, val label: String, val image: ImageVector)

/** A titled group of the icon picker. */
class MoodTagIconGroup(val title: String, val icons: List<MoodTagIcon>)

/**
 * The icons a mood tag can carry, and the default each tag shows until one is picked (the seed
 * tags keep the glyphs they always had). Built on first use: the picker and the chips need it, the
 * rest of the app never pays for ~90 vectors.
 */
object MoodTagIcons {

    /** The generic icon of a tag nobody picked an icon for. */
    const val DEFAULT_KEY = "tag"

    val GROUPS: List<MoodTagIconGroup> by lazy {
        listOf(
            MoodTagIconGroup(
                "Work & study",
                listOf(
                    MoodTagIcon("work", "Work", Icons.Outlined.Work),
                    MoodTagIcon("work_history", "Working", Icons.Outlined.WorkHistory),
                    MoodTagIcon("home_work", "Remote work", Icons.Outlined.HomeWork),
                    MoodTagIcon("business_center", "Business", Icons.Outlined.BusinessCenter),
                    MoodTagIcon("code", "Code", Icons.Outlined.Code),
                    MoodTagIcon("terminal", "Terminal", Icons.Outlined.Terminal),
                    MoodTagIcon("computer", "Computer", Icons.Outlined.Computer),
                    MoodTagIcon("school", "School", Icons.Outlined.School),
                    MoodTagIcon("menu_book", "Reading", Icons.AutoMirrored.Outlined.MenuBook),
                    MoodTagIcon("edit_note", "Writing", Icons.Outlined.EditNote),
                    MoodTagIcon("lightbulb", "Ideas", Icons.Outlined.Lightbulb),
                    MoodTagIcon("psychology", "Thinking", Icons.Outlined.Psychology),
                    MoodTagIcon("science", "Science", Icons.Outlined.Science),
                    MoodTagIcon("translate", "Languages", Icons.Outlined.Translate)
                )
            ),
            MoodTagIconGroup(
                "People",
                listOf(
                    MoodTagIcon("person", "Solo", Icons.Outlined.Person),
                    MoodTagIcon("groups", "Friends", Icons.Outlined.Groups),
                    MoodTagIcon("family_restroom", "Family", Icons.Outlined.FamilyRestroom),
                    MoodTagIcon("favorite", "Love", Icons.Outlined.Favorite),
                    MoodTagIcon("child_care", "Kids", Icons.Outlined.ChildCare),
                    MoodTagIcon("pets", "Pets", Icons.Outlined.Pets),
                    MoodTagIcon("handshake", "Meeting", Icons.Outlined.Handshake),
                    MoodTagIcon("forum", "Talking", Icons.Outlined.Forum),
                    MoodTagIcon("phone", "Call", Icons.Outlined.Phone),
                    MoodTagIcon("celebration", "Party", Icons.Outlined.Celebration),
                    MoodTagIcon("cake", "Birthday", Icons.Outlined.Cake),
                    MoodTagIcon("volunteer_activism", "Helping", Icons.Outlined.VolunteerActivism)
                )
            ),
            MoodTagIconGroup(
                "Places & travel",
                listOf(
                    MoodTagIcon("home", "Home", Icons.Outlined.Home),
                    MoodTagIcon("nature_people", "Outside", Icons.Outlined.NaturePeople),
                    MoodTagIcon("park", "Park", Icons.Outlined.Park),
                    MoodTagIcon("forest", "Nature", Icons.Outlined.Forest),
                    MoodTagIcon("beach_access", "Beach", Icons.Outlined.BeachAccess),
                    MoodTagIcon("location_city", "City", Icons.Outlined.LocationCity),
                    MoodTagIcon("shopping_bag", "Shopping", Icons.Outlined.ShoppingBag),
                    MoodTagIcon("restaurant", "Restaurant", Icons.Outlined.Restaurant),
                    MoodTagIcon("local_bar", "Drinks", Icons.Outlined.LocalBar),
                    MoodTagIcon("coffee", "Coffee", Icons.Outlined.Coffee),
                    MoodTagIcon("commute", "Commute", Icons.Outlined.Commute),
                    MoodTagIcon("directions_car", "Driving", Icons.Outlined.DirectionsCar),
                    MoodTagIcon("train", "Train", Icons.Outlined.Train),
                    MoodTagIcon("flight", "Flight", Icons.Outlined.Flight),
                    MoodTagIcon("luggage", "Trip", Icons.Outlined.Luggage)
                )
            ),
            MoodTagIconGroup(
                "Leisure",
                listOf(
                    MoodTagIcon("sports_esports", "Gaming", Icons.Outlined.SportsEsports),
                    MoodTagIcon("movie", "Movies", Icons.Outlined.Movie),
                    MoodTagIcon("tv", "TV", Icons.Outlined.Tv),
                    MoodTagIcon("music_note", "Music", Icons.Outlined.MusicNote),
                    MoodTagIcon("headphones", "Listening", Icons.Outlined.Headphones),
                    MoodTagIcon("mic", "Singing", Icons.Outlined.Mic),
                    MoodTagIcon("piano", "Instrument", Icons.Outlined.Piano),
                    MoodTagIcon("palette", "Art", Icons.Outlined.Palette),
                    MoodTagIcon("draw", "Drawing", Icons.Outlined.Draw),
                    MoodTagIcon("photo_camera", "Photos", Icons.Outlined.PhotoCamera),
                    MoodTagIcon("theater_comedy", "Theatre", Icons.Outlined.TheaterComedy),
                    MoodTagIcon("casino", "Board games", Icons.Outlined.Casino),
                    MoodTagIcon("extension", "Puzzles", Icons.Outlined.Extension),
                    MoodTagIcon("soup_kitchen", "Cooking", Icons.Outlined.SoupKitchen),
                    MoodTagIcon("outdoor_grill", "Barbecue", Icons.Outlined.OutdoorGrill),
                    MoodTagIcon("local_florist", "Gardening", Icons.Outlined.LocalFlorist),
                    MoodTagIcon("handyman", "DIY", Icons.Outlined.Handyman)
                )
            ),
            MoodTagIconGroup(
                "Body & health",
                listOf(
                    MoodTagIcon("fitness_center", "Gym", Icons.Outlined.FitnessCenter),
                    MoodTagIcon("directions_run", "Running", Icons.AutoMirrored.Outlined.DirectionsRun),
                    MoodTagIcon("directions_walk", "Walking", Icons.AutoMirrored.Outlined.DirectionsWalk),
                    MoodTagIcon("directions_bike", "Cycling", Icons.AutoMirrored.Outlined.DirectionsBike),
                    MoodTagIcon("hiking", "Hiking", Icons.Outlined.Hiking),
                    MoodTagIcon("pool", "Swimming", Icons.Outlined.Pool),
                    MoodTagIcon("sports_soccer", "Football", Icons.Outlined.SportsSoccer),
                    MoodTagIcon("sports_basketball", "Basketball", Icons.Outlined.SportsBasketball),
                    MoodTagIcon("sports_tennis", "Tennis", Icons.Outlined.SportsTennis),
                    MoodTagIcon("sports_martial_arts", "Martial arts", Icons.Outlined.SportsMartialArts),
                    MoodTagIcon("self_improvement", "Meditation", Icons.Outlined.SelfImprovement),
                    MoodTagIcon("spa", "Self-care", Icons.Outlined.Spa),
                    MoodTagIcon("bedtime", "Sleep", Icons.Outlined.Bedtime),
                    MoodTagIcon("monitor_heart", "Health", Icons.Outlined.MonitorHeart),
                    MoodTagIcon("medication", "Medication", Icons.Outlined.Medication),
                    MoodTagIcon("local_hospital", "Doctor", Icons.Outlined.LocalHospital),
                    MoodTagIcon("healing", "Recovery", Icons.Outlined.Healing)
                )
            ),
            MoodTagIconGroup(
                "Everyday",
                listOf(
                    MoodTagIcon("cleaning_services", "Chores", Icons.Outlined.CleaningServices),
                    MoodTagIcon("local_laundry_service", "Laundry", Icons.Outlined.LocalLaundryService),
                    MoodTagIcon("savings", "Money", Icons.Outlined.Savings),
                    MoodTagIcon("event", "Event", Icons.Outlined.Event),
                    MoodTagIcon("schedule", "Schedule", Icons.Outlined.Schedule),
                    MoodTagIcon("bolt", "Energy", Icons.Outlined.Bolt),
                    MoodTagIcon("wb_sunny", "Sunny", Icons.Outlined.WbSunny),
                    MoodTagIcon("nightlight", "Night", Icons.Outlined.Nightlight),
                    MoodTagIcon("mood", "Mood", Icons.Outlined.Mood),
                    MoodTagIcon("auto_awesome", "Special", Icons.Outlined.AutoAwesome),
                    MoodTagIcon("star", "Highlight", Icons.Outlined.Star),
                    MoodTagIcon("label", "Label", Icons.AutoMirrored.Outlined.Label),
                    MoodTagIcon(DEFAULT_KEY, "Tag", Icons.Outlined.Tag)
                )
            )
        )
    }

    val ALL: List<MoodTagIcon> by lazy { GROUPS.flatMap { it.icons } }

    private val byKey: Map<String, MoodTagIcon> by lazy { ALL.associateBy { it.key } }

    /** The generic tag icon ([DEFAULT_KEY]). */
    val GENERIC: MoodTagIcon by lazy { byKey.getValue(DEFAULT_KEY) }

    /** The icon saved under [key], or null when there is none or this version doesn't know it. */
    fun forKey(key: String?): MoodTagIcon? = key?.let { byKey[it] }

    /** The icon a tag shows until one is picked: the seed tags keep their old glyphs, others the generic tag. */
    fun defaultKeyFor(tagName: String): String = when (tagName) {
        "Coding" -> "code"
        "Working" -> "work_history"
        "Gaming" -> "sports_esports"
        "Chilling" -> "beach_access"
        "Solo" -> "person"
        "Family" -> "family_restroom"
        "Friends" -> "groups"
        "Home" -> "home"
        "Work" -> "home_work"
        "CDS" -> "work"
        "Out" -> "nature_people"
        else -> DEFAULT_KEY
    }

    /** What the tag shows: its picked icon when this version knows it, else its default. */
    fun resolve(tag: MoodTag): MoodTagIcon = forKey(tag.icon) ?: forKey(defaultKeyFor(tag.name)) ?: GENERIC

    /** For a tag known only by name (a past entry's activity): its icon in [tags], else its default. */
    fun resolve(tagName: String, tags: List<MoodTag>): MoodTagIcon =
        resolve(tags.firstOrNull { it.name == tagName } ?: MoodTag(tagName))
}

package cloud.wafflecommons.pixelbrainreader.ui.gamification

import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass

object GamificationAssets {

    fun getHeroDrawable(characterClass: CharacterClass): Int {
        return when (characterClass) {
            CharacterClass.PEASANT -> R.drawable.hero_novice
            CharacterClass.WARRIOR -> R.drawable.hero_warrior
            CharacterClass.MAGE -> R.drawable.hero_sage
            CharacterClass.CLERIC -> R.drawable.hero_sentinel
            CharacterClass.BARD -> R.drawable.hero_creator
            CharacterClass.ROGUE -> R.drawable.hero_novice // Fallback
        }
    }

    fun getAttributeIcon(attribute: Attribute): Int {
        return when (attribute) {
            Attribute.VIG -> R.drawable.attr_vig
            Attribute.MND -> R.drawable.attr_mnd
            Attribute.END -> R.drawable.attr_end
            Attribute.SOC -> R.drawable.attr_soc
            Attribute.CRE -> R.drawable.attr_cre
            Attribute.INT -> R.drawable.attr_mnd // Fallback or reuse
            Attribute.FTH -> R.drawable.attr_cre // Fallback or reuse
        }
    }
}

package de.noelkoeppen.fossilcollector.ui.theme

import androidx.annotation.DrawableRes
import de.noelkoeppen.fossilcollector.R

/**
 * Eigene, flache Vektor-Icons der App (Kapitel 8.5, AP-26) als Drawable-Ressourcen in
 * `res/drawable/ic_*.xml`. Alle sind einfarbig weiss gezeichnet und werden von `Icon` eingefaerbt.
 * Ersetzen die vorlaeufigen Material-Symbole; `material-icons-core` wird nicht mehr gebraucht.
 */
object AppIcons {
    @DrawableRes val TabDig: Int = R.drawable.ic_tab_dig
    @DrawableRes val TabLab: Int = R.drawable.ic_tab_lab
    @DrawableRes val TabMuseum: Int = R.drawable.ic_tab_museum
    @DrawableRes val TabBook: Int = R.drawable.ic_tab_book
    @DrawableRes val TabFame: Int = R.drawable.ic_tab_fame

    /** Waehrungen der oberen Leiste. */
    @DrawableRes val Money: Int = R.drawable.ic_money
    @DrawableRes val ResearchPoints: Int = R.drawable.ic_research
    @DrawableRes val Fame: Int = R.drawable.ic_star

    @DrawableRes val Settings: Int = R.drawable.ic_settings
    @DrawableRes val Back: Int = R.drawable.ic_back

    /** Completion-Stufe 1 bzw. gekaufter Skill. */
    @DrawableRes val Check: Int = R.drawable.ic_check

    /** Completion-Stufe 2 bzw. Capstone. */
    @DrawableRes val Star: Int = R.drawable.ic_star
    @DrawableRes val Lock: Int = R.drawable.ic_lock
    @DrawableRes val Warning: Int = R.drawable.ic_warning
}

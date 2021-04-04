package org.arend.navigation

import org.arend.ArendTestBase

class ArendSymbolNavigationContributorTest : ArendTestBase() {
    fun `test contains Prelude definitions`() {
        val contributor = ArendSymbolNavigationContributor()
        val names = contributor.getNames(project, true)
        assertContainsElements(names.asList(), "Path", "coe", "iso", "Nat", "+", "*")
    }

    fun `test contains Prelude definitions by name`() {
        val contributor = ArendSymbolNavigationContributor()
        assertSameElements(getByName(contributor, "coe"), "coe")
        assertSameElements(getByName(contributor, "Nat"), "Nat")
        assertSameElements(getByName(contributor, "+"), "+")
    }

    private fun getByName(contributor: ArendSymbolNavigationContributor, name: String): List<String> =
        contributor.getItemsByName(name, name, project, true).map { it.name!! }
}
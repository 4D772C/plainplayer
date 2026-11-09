package de.plainplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/**
 * Regression tests for the path- and tag-based rules that decide how files become albums and books.
 * Pure JVM tests: no device or emulator needed. Run with `./gradlew test`.
 */
class LibraryRulesTest {

    // ---- Albums ----------------------------------------------------------------------------------

    @Test
    fun discFoldersBelongToTheirAlbum() {
        assertEquals("Artist/Album", albumFolder("Artist/Album/CD2"))
        assertEquals("Artist/Album", albumFolder("Artist/Album/cd 1"))
        assertEquals("Artist/Album", albumFolder("Artist/Album/Disc 01"))
        assertEquals("Artist/Album", albumFolder("Artist/Album/Disc 1 - The Early Years"))
        assertEquals("Artist/Album", albumFolder("Artist/Album"))
        assertEquals("Artist/CD Singles", albumFolder("Artist/CD Singles"))
        assertEquals("Artist/Discovery", albumFolder("Artist/Discovery"))
    }

    @Test
    fun albumKeyIgnoresArtistAndCase() {
        assertEquals(albumKeyOf("Band/Album", "Album"), albumKeyOf("Band/Album", " album "))
        assertTrue(albumKeyOf("A/Greatest Hits", "Greatest Hits") != albumKeyOf("B/Greatest Hits", "Greatest Hits"))
    }

    @Test
    fun featuringIsRemovedFromArtist() {
        assertEquals("Band", primaryArtist("Band feat. Someone"))
        assertEquals("Band", primaryArtist("Band ft. X & Y"))
        assertEquals("Band", primaryArtist("Band (feat. Someone)"))
        assertEquals("Band", primaryArtist("Band [Featuring Z]"))
        assertEquals("Feathers", primaryArtist("Feathers"))
        assertEquals("Soft Cell", primaryArtist("Soft Cell"))
        assertEquals("Left Boy", primaryArtist("Left Boy"))
    }

    // ---- Audiobooks ------------------------------------------------------------------------------

    @Test
    fun musicIsNotAnAudiobook() {
        assertNull(audiobookKeyOf("Music/Album/01.mp3"))
        assertNull(audiobookKeyOf("01.mp3"))
    }

    @Test
    fun oneFolderIsOneBook() {
        assertEquals("Hörbücher/Der Hobbit", audiobookKeyOf("Hörbücher/Der Hobbit/01.mp3"))
        assertEquals("Hörbücher/Tolkien/Der Hobbit", audiobookKeyOf("Hörbücher/Tolkien/Der Hobbit/01.mp3"))
        assertEquals("Musik/Hörbücher/Buch", audiobookKeyOf("Musik/Hörbücher/Buch/1.mp3"))
        assertEquals("AUDIOBOOKS/Book", audiobookKeyOf("AUDIOBOOKS/Book/1.mp3")) // any case
    }

    @Test
    fun partFoldersBelongToTheirBook() {
        val book = "Hörbücher/Tolkien/Der Hobbit"
        assertEquals(book, audiobookKeyOf("$book/CD1/01.mp3"))
        assertEquals(book, audiobookKeyOf("$book/Disc 2/01.mp3"))
        assertEquals(book, audiobookKeyOf("$book/Part 1/01.mp3"))
        assertEquals(book, audiobookKeyOf("$book/Teil 3/x.mp3"))
        assertEquals(book, audiobookKeyOf("$book/Seite A1/x.mp3"))
        assertEquals(book, audiobookKeyOf("$book/01/x.mp3"))
    }

    @Test
    fun volumesOfASeriesAreSeparateBooks() {
        assertEquals("Hörbücher/Serie/Band 1", audiobookKeyOf("Hörbücher/Serie/Band 1/01.mp3"))
        assertEquals("Hörbücher/Serie/Band 2", audiobookKeyOf("Hörbücher/Serie/Band 2/01.mp3"))
    }

    @Test
    fun looseFilesAreBooksOfTheirOwn() {
        assertEquals("Hörbücher/Einzeln.mp3", audiobookKeyOf("Hörbücher/Einzeln.mp3"))
    }

    @Test
    fun decomposedUmlautsAreRecognised() {
        // macOS and some file systems store "ü" as "u" + combining diaeresis.
        val nfd = Normalizer.normalize("Hörbücher", Normalizer.Form.NFD)
        assertEquals("$nfd/Buch", audiobookKeyOf("$nfd/Buch/1.mp3"))
    }

    @Test
    fun authorComesFromTheFolderAboveTheBook() {
        assertEquals("Tolkien", audiobookAuthorFolder("Hörbücher/Tolkien/Der Hobbit"))
        assertNull(audiobookAuthorFolder("Hörbücher/Der Hobbit"))
        assertNull(audiobookAuthorFolder("Musik/Hörbücher/Der Hobbit"))
    }

    // ---- Ordering --------------------------------------------------------------------------------

    @Test
    fun naturalOrderSortsNumbersByValue() {
        val files = listOf("B/CD2/01 Teil.mp3", "B/CD1/10 Teil.mp3", "B/CD1/2 Teil.mp3", "B/CD1/01 Teil.mp3", "B/CD10/01.mp3")
        assertEquals(
            listOf("B/CD1/01 Teil.mp3", "B/CD1/2 Teil.mp3", "B/CD1/10 Teil.mp3", "B/CD2/01 Teil.mp3", "B/CD10/01.mp3"),
            files.sortedWith(NaturalOrder),
        )
        assertTrue(NaturalOrder.compare("part 2", "Part 10") < 0)
    }

    // ---- Covers ----------------------------------------------------------------------------------

    @Test
    fun coversAreDecodedWithAPowerOfTwoSample() {
        assertEquals(1, CoverStore.sampleSize(500, 500, 512))
        assertEquals(2, CoverStore.sampleSize(1024, 1024, 512))
        assertEquals(4, CoverStore.sampleSize(3000, 3000, 512))
        assertEquals(4, CoverStore.sampleSize(3000, 1000, 512))
    }
}

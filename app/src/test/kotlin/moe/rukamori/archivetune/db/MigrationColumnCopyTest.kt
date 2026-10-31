package moe.rukamori.archivetune.db

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationColumnCopyTest {
    @Test fun nullableLegacyAlbumPositionUsesZeroOnlyWhenNull() {
        assertEquals("COALESCE(`index`, 0)", migrationColumnExpression("index", notNull = true, fallback = "0"))
    }

    @Test fun requiredColumnsUseTheirDeclaredDefault() {
        assertEquals("COALESCE(`explicit`, 0)", migrationColumnExpression("explicit", notNull = true, fallback = "0"))
        assertEquals("COALESCE(`title`, '')", migrationColumnExpression("title", notNull = true, fallback = "''"))
    }

    @Test fun nullableValuesAndMissingColumnsKeepTheirExistingMeaning() {
        assertEquals("`albumId`", migrationColumnExpression("albumId", notNull = false, fallback = "NULL"))
        assertEquals("0", migrationColumnExpression(null, notNull = true, fallback = "0"))
        assertEquals("NULL", migrationColumnExpression(null, notNull = false, fallback = "NULL"))
    }
}

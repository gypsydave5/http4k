package org.http4k.testing

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.throws
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class NamedSourceApproverTest {

    private val body = "content"
    private val baseFile = Files.createTempDirectory(javaClass.name).toFile()
    private val testName = "somename" + Random.nextLong()
    private val actualFile = File(baseFile, "$testName.actual")
    private val approvedFile = File(baseFile, "$testName.approved")
    private val approver = NamedResourceApprover(testName, ApprovalContent.HttpTextBody(), FileSystemApprovalSource(baseFile))

    @Test
    fun `when no approval recorded, create actual and throw`() {
        assertThat({ approver.assertApproved(Response(OK).body(body)) }, throws<AssertionFailedError>())
        assertThat(actualFile.exists(), equalTo(true))
        assertThat(actualFile.readText(), equalTo(body))
        assertThat(approvedFile.exists(), equalTo(false))
    }

    @Test
    fun `when mismatch, overwrite actual`() {
        approvedFile.writeText("some other value")
        actualFile.writeText("previous content")
        assertThat({ approver.assertApproved(Response(OK).body(body)) }, throws<AssertionError>())
        assertThat(actualFile.readText(), equalTo(body))
        assertThat(approvedFile.readText(), equalTo("some other value"))
    }

    @Test
    fun `when no approval recorded and no actual content, don't write actual or approved`() {
        approver.assertApproved(Response(OK))
        assertThat(actualFile.exists(), equalTo(false))
        assertThat(approvedFile.exists(), equalTo(false))
    }

    @Test
    fun `when match, don't write actual`() {
        approvedFile.writeText(body)
        approver.assertApproved(Response(OK).body(body))
        assertThat(actualFile.exists(), equalTo(false))
        assertThat(approvedFile.readText(), equalTo(body))
    }

    @Test
    fun `uses file name suffix`() {
        assertThat({ approver.withNameSuffix("suffix").assertApproved(Response(OK).body(body)) }, throws<AssertionFailedError>())
        val actualFile = File(baseFile, "${testName}.suffix.actual")
        assertThat(actualFile.exists(), equalTo(true))
        assertThat(actualFile.readText(), equalTo(body))
        assertThat(approvedFile.exists(), equalTo(false))
    }

@Test
    fun `uses file name suffix with transformer`() {
        val expected = "transformed"

        val newTransformer = ApprovalTransformer {
            ApprovalTransformer.StringWithNormalisedLineEndings()(it)
                .replace(body, expected)
        }

        File(baseFile, "${testName}.suffix.approved").writeText(expected)

        val approver = NamedResourceApprover(
            testName,
            ApprovalContent.HttpTextBody(),
            FileSystemApprovalSource(baseFile), newTransformer
        ).withNameSuffix("suffix")

        approver.assertApproved(Response(OK).body(body))
    }
}

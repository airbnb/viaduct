package viaduct.engine.runtime2.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolverApplicationIdentity
import viaduct.engine.runtime2.arbitrary.ResolverApplicationKey
import viaduct.engine.runtime2.arbitrary.ResolverOccurrenceApplicationIdentity
import viaduct.engine.runtime2.arbitrary.ResolverOccurrenceApplicationKey
import viaduct.engine.runtime2.arbitrary.resolutionFingerprint
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.engineResultOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class CheckerQueryResolverWitnessTest {
    @Test
    fun `checker-only Query roots are counted once per distinct OER in every application oracle`() {
        val world =
            TestWorld.fromDSL(
                schemaSDL = "extend type Query { value: Int! @resolver(result: 7) marker: Int! @resolver(result: 1) }",
            ).assumptions
        val query = world.schema.requireQueryTypeDef()
        val value = world.schema.requireObjectField("Query", "value")
        val marker = world.schema.requireObjectField("Query", "marker")
        val valueKey = ObjectEngineResult.GroundKey.of(value, emptyMap())
        val primary = world.engineResultOf("Query")
        val first = world.engineResultOf("Query") { "value" resolvesTo 7 }
        val second = world.engineResultOf("Query") { "value" resolvesTo 7 }
        val checkerObserver = CorrectnessCheckerObserver()
        checkerObserver.onCheckerQueryFragmentPrepared(
            ResolverTarget.FieldCheckerTarget(value),
            ResolverOccurrenceId.at(primary, listOf(valueKey)),
            first,
        )
        checkerObserver.onCheckerQueryFragmentPrepared(
            ResolverTarget.TypeCheckerTarget(query),
            ResolverOccurrenceId.at(primary, emptyList()),
            first,
        )
        checkerObserver.onCheckerQueryFragmentPrepared(
            ResolverTarget.FieldCheckerTarget(marker),
            ResolverOccurrenceId.at(primary, listOf(ObjectEngineResult.GroundKey.of(marker, emptyMap()))),
            second,
        )
        val operation =
            SharedOperationContext.create(
                world,
                resolverObserver = CorrectnessResolverObserver(),
                checkerObserver = checkerObserver,
            )
        val key = ResolverApplicationKey(FieldCoordinate("Query", "value"), Arguments.Resolved.of(value, emptyMap()))
        val identity = ResolverApplicationIdentity(key, engineObjectDataOf(query).resolutionFingerprint())
        val occurrences = listOf(first, second).map { ResolverOccurrenceId.at(it, listOf(valueKey)) }

        assertEquals(mapOf(identity to 2), primary.registeredResolverApplicationIdentityCounts(operation))
        assertEquals(
            occurrences.associate { ResolverOccurrenceApplicationIdentity(it, identity) to 1 },
            primary.registeredResolverOccurrenceApplicationIdentityCounts(operation),
        )
        assertEquals(
            occurrences.associate { ResolverOccurrenceApplicationKey(it, key) to 1 },
            primary.registeredResolverOccurrenceApplicationKeyCounts(operation),
        )
    }
}

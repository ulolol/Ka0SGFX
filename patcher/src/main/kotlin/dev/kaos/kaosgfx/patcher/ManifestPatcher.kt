package dev.kaos.kaosgfx.patcher

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.ByteArrayInputStream

object ManifestPatcher {
    private const val ATTR_APP_COMPONENT_FACTORY = "appComponentFactory"
    private const val ID_APP_COMPONENT_FACTORY = 0x0101057a
    private const val ATTR_NAME = "name"
    private const val ID_NAME = 0x01010003
    private const val ATTR_VALUE = "value"
    private const val ID_VALUE = 0x01010024
    private const val ATTR_EXTRACT_NATIVE_LIBS = "extractNativeLibs"
    private const val ID_EXTRACT_NATIVE_LIBS = 0x010104ea

    const val BOOTSTRAP_FACTORY_CLASS = "dev.kaos.kaosgfx.bootstrap.TurnipAppComponentFactory"
    const val META_ORIGINAL_FACTORY = "dev.kaos.kaosgfx.ORIGINAL_APP_COMPONENT_FACTORY"

    data class PatchResult(
        val patchedXmlBytes: ByteArray,
        val originalFactory: String?,
        val wasAlreadyPatched: Boolean
    )

    fun patchManifestBytes(inputBytes: ByteArray): PatchResult {
        val manifest = AndroidManifestBlock()
        manifest.readBytes(ByteArrayInputStream(inputBytes))

        val appElement = manifest.applicationElement
            ?: throw IllegalStateException("Manifest does not contain an <application> element")

        // 1. Check existing appComponentFactory
        val existingAttr = appElement.searchAttributeByResourceId(ID_APP_COMPONENT_FACTORY)
            ?: appElement.searchAttributeByName(ATTR_APP_COMPONENT_FACTORY)

        val existingFactory = existingAttr?.valueAsString
        if (existingFactory == BOOTSTRAP_FACTORY_CLASS) {
            return PatchResult(inputBytes, null, wasAlreadyPatched = true)
        }

        // 2. If original factory existed, record it in <meta-data>
        if (!existingFactory.isNullOrBlank()) {
            val metaDataElement = appElement.createChildElement("meta-data")
            val nameAttr = metaDataElement.getOrCreateAndroidAttribute(ATTR_NAME, ID_NAME)
            nameAttr.valueAsString = META_ORIGINAL_FACTORY

            val valAttr = metaDataElement.getOrCreateAndroidAttribute(ATTR_VALUE, ID_VALUE)
            valAttr.valueAsString = existingFactory
        }

        // 3. Point appComponentFactory to TurnipAppComponentFactory
        val factoryAttr = appElement.getOrCreateAndroidAttribute(ATTR_APP_COMPONENT_FACTORY, ID_APP_COMPONENT_FACTORY)
        factoryAttr.valueAsString = BOOTSTRAP_FACTORY_CLASS

        // 4. Set extractNativeLibs="true"
        val extractLibsAttr = appElement.getOrCreateAndroidAttribute(ATTR_EXTRACT_NATIVE_LIBS, ID_EXTRACT_NATIVE_LIBS)
        extractLibsAttr.setValueAsBoolean(true)

        // 5. Refresh string pool & chunk headers, then serialize back
        manifest.refreshFull()
        return PatchResult(manifest.bytes, existingFactory, wasAlreadyPatched = false)
    }
}

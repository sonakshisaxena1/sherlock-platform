// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.jsonSchema.impl.light.nodes

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.asSafely
import com.jetbrains.jsonSchema.extension.JsonSchemaValidation
import com.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.IfThenElse
import com.jetbrains.jsonSchema.impl.JsonSchemaObject
import com.jetbrains.jsonSchema.impl.JsonSchemaType
import com.jetbrains.jsonSchema.impl.MergedJsonSchemaObject
import com.jetbrains.jsonSchema.impl.light.legacy.LegacyJsonSchemaObjectMerger
import com.jetbrains.jsonSchema.impl.light.versions.JsonSchemaInterpretationStrategy

internal class InheritedJsonSchemaObjectView(
  override val base: JsonSchemaObject,
  override val other: JsonSchemaObject,
) : JsonSchemaObject(), MergedJsonSchemaObject {
  private fun getMergedSchemaInterpretationStrategy(): JsonSchemaInterpretationStrategy? {
    return other.rootSchemaObject.asSafely<RootJsonSchemaObjectBackedByJackson>()?.schemaInterpretationStrategy
  }

  override fun getPointer(): String {
    return other.pointer
  }

  override fun getFileUrl(): String? {
    return other.fileUrl
  }

  override fun getRawFile(): VirtualFile? {
    return other.rawFile
  }

  // important to see the following two methods together - they must resolve other's ref according to the resolver logic
  override fun getRef(): String? {
    return other.ref
  }

  override fun readChildNodeValue(vararg childNodeName: String): String? {
    return baseIfConditionOrOtherWithArgument(JsonSchemaObject::readChildNodeValue, childNodeName, String?::isNotBlank)
  }

  override fun hasChildNode(vararg childNodeName: String): Boolean {
    return other.hasChildNode(*childNodeName)
  }

  override fun hasChildFieldsExcept(namesToSkip: Array<String>): Boolean {
    return booleanOrWithArgument(JsonSchemaObject::hasChildFieldsExcept, namesToSkip)
  }

  override fun getValidations(type: JsonSchemaType?, value: JsonValueAdapter): Iterable<JsonSchemaValidation> {
    return getMergedSchemaInterpretationStrategy()
      ?.getValidations(this, type, value)
      .orEmpty()
      .asIterable()
  }

  override fun getRootSchemaObject(): JsonSchemaObject {
    return base.rootSchemaObject
  }

  override fun getConstantSchema(): Boolean? {
    return booleanAndNullable(JsonSchemaObject::getConstantSchema)
  }

  override fun isValidByExclusion(): Boolean {
    return other.isValidByExclusion
  }

  override fun getDefinitionNames(): Iterator<String> {
    return sequence<String> {
      yieldAll(base.definitionNames)
      yieldAll(other.definitionNames)
    }.distinct().iterator()
  }

  override fun getDefinitionByName(name: String): JsonSchemaObject? {
    val baseDef = base.getDefinitionByName(name)
    if (baseDef == null) return other.getDefinitionByName(name)

    val otherDef = other.getDefinitionByName(name)
    if (otherDef == null) return baseDef

    return LightweightJsonSchemaObjectMerger.mergeObjects(baseDef, otherDef, otherDef)
  }

  override fun getPropertyNames(): Iterator<String> {
    return sequence<String> {
      yieldAll(base.propertyNames)
      yieldAll(other.propertyNames)
    }.distinct().iterator()
  }

  override fun getPropertyByName(name: String): JsonSchemaObject? {
    val baseProp = base.getPropertyByName(name)
    if (baseProp == null) return other.getPropertyByName(name)

    val otherProp = other.getPropertyByName(name)
    if (otherProp == null) return baseProp

    return LightweightJsonSchemaObjectMerger.mergeObjects(baseProp, otherProp, otherProp)
  }

  override fun getSchemaDependencyNames(): Iterator<String> {
    return sequence<String> {
      yieldAll(base.schemaDependencyNames)
      yieldAll(other.schemaDependencyNames)
    }.distinct().iterator()

  }

  override fun getSchemaDependencyByName(name: String): JsonSchemaObject? {
    val baseDef = base.getSchemaDependencyByName(name)
    if (baseDef == null) return other.getSchemaDependencyByName(name)

    val otherDef = other.getSchemaDependencyByName(name)
    if (otherDef == null) return baseDef

    return LightweightJsonSchemaObjectMerger.mergeObjects(baseDef, otherDef, otherDef)
  }

  override fun hasPatternProperties(): Boolean {
    return other.hasPatternProperties()
  }

  override fun getType(): JsonSchemaType? {
    return other.type
  }

  override fun getMultipleOf(): Number? {
    return baseIfConditionOrOther(JsonSchemaObject::getMultipleOf, Any?::isNotNull)
  }

  override fun getMaximum(): Number? {
    return baseIfConditionOrOther(JsonSchemaObject::getMaximum, Any?::isNotNull)
  }

  override fun isExclusiveMaximum(): Boolean {
    return booleanOr(JsonSchemaObject::isExclusiveMaximum)
  }

  override fun getExclusiveMaximumNumber(): Number? {
    return baseIfConditionOrOther(JsonSchemaObject::getExclusiveMaximumNumber, Any?::isNotNull)
  }

  override fun getExclusiveMinimumNumber(): Number? {
    return baseIfConditionOrOther(JsonSchemaObject::getExclusiveMinimumNumber, Any?::isNotNull)
  }

  override fun getMinimum(): Number? {
    return baseIfConditionOrOther(JsonSchemaObject::getMinimum, Any?::isNotNull)
  }

  override fun isExclusiveMinimum(): Boolean {
    return booleanOr(JsonSchemaObject::isExclusiveMinimum)
  }

  override fun getMaxLength(): Int? {
    return baseIfConditionOrOther(JsonSchemaObject::getMaxLength, Any?::isNotNull)
  }

  override fun getMinLength(): Int? {
    return baseIfConditionOrOther(JsonSchemaObject::getMinLength, Any?::isNotNull)
  }

  override fun getPattern(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getPattern, Any?::isNotNull)
  }

  override fun getAdditionalPropertiesAllowed(): Boolean {
    return booleanAnd(JsonSchemaObject::getAdditionalPropertiesAllowed)
  }

  override fun hasOwnExtraPropertyProhibition(): Boolean {
    return !other.additionalPropertiesAllowed
  }

  override fun getPropertyNamesSchema(): JsonSchemaObject? {
    return baseIfConditionOrOther(JsonSchemaObject::getPropertyNamesSchema, Any?::isNotNull)
  }

  override fun getAdditionalPropertiesSchema(): JsonSchemaObject? {
    return baseIfConditionOrOther(JsonSchemaObject::getAdditionalPropertiesSchema, Any?::isNotNull)
  }

  override fun getUnevaluatedPropertiesSchema(): JsonSchemaObject? {
    return other.unevaluatedPropertiesSchema
  }

  override fun getAdditionalItemsAllowed(): Boolean? {
    return baseIfConditionOrOther(JsonSchemaObject::getAdditionalItemsAllowed, Any?::isNotNull)
  }

  override fun getDeprecationMessage(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getDeprecationMessage, String?::isNotBlank)
  }

  override fun getAdditionalItemsSchema(): JsonSchemaObject? {
    return baseIfConditionOrOther(JsonSchemaObject::getAdditionalItemsSchema, Any?::isNotNull)
  }

  override fun getItemsSchema(): JsonSchemaObject? {
    return baseIfConditionOrOther(JsonSchemaObject::getItemsSchema, Any?::isNotNull)
  }

  override fun getUnevaluatedItemsSchema(): JsonSchemaObject? {
    return other.unevaluatedItemsSchema
  }

  override fun getContainsSchema(): JsonSchemaObject? {
    return baseIfConditionOrOther(JsonSchemaObject::getContainsSchema, Any?::isNotNull)
  }

  override fun getItemsSchemaList(): List<JsonSchemaObject>? {
    return mergeLists(JsonSchemaObject::getItemsSchemaList)
  }

  override fun getMaxItems(): Int? {
    return baseIfConditionOrOther(JsonSchemaObject::getMaxItems, Any?::isNotNull)
  }

  override fun getMinItems(): Int? {
    return baseIfConditionOrOther(JsonSchemaObject::getMinItems, Any?::isNotNull)
  }

  override fun isUniqueItems(): Boolean {
    return baseIfConditionOrOther(JsonSchemaObject::isUniqueItems, Boolean::isNotNull)
  }

  override fun getMaxProperties(): Int? {
    return baseIfConditionOrOther(JsonSchemaObject::getMaxProperties, Any?::isNotNull)
  }

  override fun getMinProperties(): Int? {
    return baseIfConditionOrOther(JsonSchemaObject::getMinProperties, Any?::isNotNull)
  }

  override fun getRequired(): Set<String>? {
    return other.required
  }

  override fun getPropertyDependencies(): Map<String, List<String>>? {
    return mergeMaps(JsonSchemaObject::getPropertyDependencies)
  }

  override fun getSchemaDependencies(): Map<String, JsonSchemaObject>? {
    return mergeMaps(JsonSchemaObject::getSchemaDependencies)
  }

  override fun getEnum(): List<Any>? {
    return baseIfConditionOrOther(JsonSchemaObject::getEnum, Any?::isNotNull)
  }

  override fun getAllOf(): List<JsonSchemaObject>? {
    return other.allOf
  }

  override fun getAnyOf(): List<JsonSchemaObject>? {
    return other.anyOf
  }

  override fun getOneOf(): List<JsonSchemaObject>? {
    return other.oneOf
  }

  override fun getNot(): JsonSchemaObject? {
    return other.not
  }

  override fun getIfThenElse(): List<IfThenElse>? {
    return other.ifThenElse
  }

  override fun getTypeVariants(): Set<JsonSchemaType>? {
    return LegacyJsonSchemaObjectMerger.mergeTypeVariantSets(base.typeVariants, other.typeVariants).types
  }

  override fun isRefRecursive(): Boolean {
    return booleanOr(JsonSchemaObject::isRefRecursive)
  }

  override fun isRecursiveAnchor(): Boolean {
    return booleanOr(JsonSchemaObject::isRecursiveAnchor)
  }

  override fun getDefault(): Any? {
    return baseIfConditionOrOther(JsonSchemaObject::getDefault, Any?::isNotNull)
  }

  override fun getExampleByName(name: String): JsonSchemaObject? {
    return baseIfConditionOrOtherWithArgument(JsonSchemaObject::getExampleByName, name, Any?::isNotNull)
  }

  override fun getFormat(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getFormat, Any?::isNotNull)
  }

  override fun getId(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getId, Any?::isNotNull)
  }

  override fun getSchema(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getSchema, Any?::isNotNull)
  }

  override fun getDescription(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getDescription, String?::isNotBlank)
  }

  override fun getTitle(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getTitle, String?::isNotBlank)
  }

  override fun getMatchingPatternPropertySchema(name: String): JsonSchemaObject? {
    return baseIfConditionOrOtherWithArgument(JsonSchemaObject::getMatchingPatternPropertySchema, name, Any?::isNotNull)
  }

  override fun checkByPattern(value: String): Boolean {
    return booleanOrWithArgument(JsonSchemaObject::checkByPattern, value)
  }

  override fun getPatternError(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getPatternError, Any?::isNotNull)
  }

  override fun findRelativeDefinition(ref: String): JsonSchemaObject? {
    return baseIfConditionOrOtherWithArgument(JsonSchemaObject::findRelativeDefinition, ref, Any?::isNotNull)
  }

  override fun getEnumMetadata(): Map<String, Map<String, String>>? {
    return mergeMaps(JsonSchemaObject::getEnumMetadata)
  }

  override fun getTypeDescription(shortDesc: Boolean): String? {
    return baseIfConditionOrOtherWithArgument(JsonSchemaObject::getTypeDescription, shortDesc, Any?::isNotNull)
  }

  override fun getHtmlDescription(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getHtmlDescription, String?::isNotBlank)
  }

  override fun getExample(): Map<String, Any>? {
    return baseIfConditionOrOther(JsonSchemaObject::getExample, Any?::isNotNull)
  }

  override fun getBackReference(): JsonSchemaObject? {
    return baseIfConditionOrOther(JsonSchemaObject::getBackReference, Any?::isNotNull)
  }

  override fun isForceCaseInsensitive(): Boolean {
    return booleanOr(JsonSchemaObject::isForceCaseInsensitive)
  }

  override fun getLanguageInjection(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getLanguageInjection, Any?::isNotNull)
  }

  override fun getLanguageInjectionPrefix(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getLanguageInjectionPrefix, Any?::isNotNull)
  }

  override fun getLanguageInjectionPostfix(): String? {
    return baseIfConditionOrOther(JsonSchemaObject::getLanguageInjectionPostfix, Any?::isNotNull)
  }

  override fun isShouldValidateAgainstJSType(): Boolean {
    return booleanOr(JsonSchemaObject::isShouldValidateAgainstJSType)
  }

  override fun resolveRefSchema(service: JsonSchemaService): JsonSchemaObject? {
    return other.resolveRefSchema(service) ?: base.resolveRefSchema(service)
  }

  override fun mergeTypes(selfType: JsonSchemaType?,
                          otherType: JsonSchemaType?,
                          otherTypeVariants: MutableSet<JsonSchemaType>?): JsonSchemaType? {
    throw UnsupportedOperationException("Must not call mergeTypes on light aggregated object")
  }

  override fun mergeTypeVariantSets(self: MutableSet<JsonSchemaType>?, other: MutableSet<JsonSchemaType>?): MutableSet<JsonSchemaType> {
    throw UnsupportedOperationException("Must not call mergeTypeVariantSets on light aggregated object")
  }

  override fun mergeValues(other: JsonSchemaObject) {
    throw UnsupportedOperationException("Must not call mergeValues on light aggregated object")
  }

  override fun getProperties(): Map<String, JsonSchemaObject> {
    throw UnsupportedOperationException("Must not call propertiesMap on light aggregated object")
  }

  override fun getDefinitionsMap(): Map<String, JsonSchemaObject>? {
    throw UnsupportedOperationException("Must not call definitionsMap on light aggregated object")
  }
}

private fun <T, V> InheritedJsonSchemaObjectView.baseIfConditionOrOtherWithArgument(
  memberReference: JsonSchemaObject.(V) -> T,
  argument: V,
  condition: (T) -> Boolean,
): T {
  return baseIfConditionOrOtherWithArgument(other, base, memberReference, argument, condition)
}

private fun <T> InheritedJsonSchemaObjectView.baseIfConditionOrOther(memberReference: JsonSchemaObject.() -> T, condition: (T) -> Boolean): T {
  return baseIfConditionOrOther(other, base, memberReference, condition)
}

private fun <V> InheritedJsonSchemaObjectView.booleanOrWithArgument(memberReference: JsonSchemaObject.(V) -> Boolean, argument: V): Boolean {
  return booleanOrWithArgument(other, base, memberReference, argument)
}

private fun InheritedJsonSchemaObjectView.booleanAndNullable(memberReference: JsonSchemaObject.() -> Boolean?): Boolean? {
  return booleanAndNullable(other, base, memberReference)
}

private fun InheritedJsonSchemaObjectView.booleanAnd(memberReference: JsonSchemaObject.() -> Boolean): Boolean {
  return booleanAnd(other, base, memberReference)
}

private fun InheritedJsonSchemaObjectView.booleanOr(memberReference: JsonSchemaObject.() -> Boolean): Boolean {
  return booleanOr(other, base, memberReference)
}

/*
 * Copyright 2012 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.util.repo;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.lilyproject.repository.api.FieldType;
import org.lilyproject.repository.api.FieldTypeNotFoundException;
import org.lilyproject.repository.api.QName;
import org.lilyproject.repository.api.RepositoryException;
import org.lilyproject.repository.api.SchemaId;
import org.lilyproject.repository.api.Scope;
import org.lilyproject.repository.api.TypeManager;

public class FieldTypeUtil {
    private FieldTypeUtil() {
    }

    public static Map<Scope, Set<FieldType>> getFieldTypeAndScope(Set<SchemaId> fieldIds, FieldFilter fieldFilter,
            TypeManager typeManager) throws RepositoryException, InterruptedException {

        // Could be written more elegantly using Multimaps.index, but we want to limit dependencies
        Map<Scope, Set<FieldType>> result = new EnumMap<Scope, Set<FieldType>>(Scope.class);
        for (Scope scope : Scope.values()) {
            result.put(scope, new HashSet<FieldType>());
        }

        for (SchemaId fieldId : fieldIds) {
            FieldType fieldType;
            try {
                fieldType = typeManager.getFieldTypeById(fieldId);
            } catch (FieldTypeNotFoundException e) {
                // A field whose field type does not exist: skip it
                continue;
            }
            if (fieldFilter.accept(fieldType)) {
                result.get(fieldType.getScope()).add(fieldType);
            }
        }

        return result;
    }

    public static Map<Scope, Set<SchemaId>> getFieldTypeIdsAndScope(Set<SchemaId> fieldIds, FieldFilter fieldFilter,
            TypeManager typeManager) throws RepositoryException, InterruptedException {
        Map<Scope, Set<SchemaId>> result = new HashMap<Scope, Set<SchemaId>>();
        Map<Scope, Set<FieldType>> fieldTypesByScope = getFieldTypeAndScope(fieldIds, fieldFilter, typeManager);
        for (Scope scope: fieldTypesByScope.keySet()) {
            Set<SchemaId> schemaIds = new HashSet<SchemaId>();
            for (FieldType t: fieldTypesByScope.get(scope)) {
                schemaIds.add(t.getId());
            }
            result.put(scope, schemaIds);
        }

        return result;
    }

    public static Map<Scope, Set<QName>> getFieldTypeNamesAndScope(Set<SchemaId> fieldIds, FieldFilter fieldFilter,
                TypeManager typeManager) throws RepositoryException, InterruptedException {
        Map<Scope, Set<QName>> result = new HashMap<Scope, Set<QName>>();
        Map<Scope, Set<FieldType>> fieldTypesByScope = getFieldTypeAndScope(fieldIds, fieldFilter, typeManager);
        for (Scope scope: fieldTypesByScope.keySet()) {
            Set<QName> schemaIds = new HashSet<QName>();
            for (FieldType t: fieldTypesByScope.get(scope)) {
                schemaIds.add(t.getName());
            }
            result.put(scope, schemaIds);
        }

        return result;
    }
}

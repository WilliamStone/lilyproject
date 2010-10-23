package org.lilyproject.tools.import_.json;

import org.lilyproject.repository.api.QName;

public class QNameConverter {
    public static QName fromJson(String name, Namespaces namespaces) throws JsonFormatException {
        int pos = name.indexOf('$');
        if (pos == -1) {
            throw new JsonFormatException("Invalid qualified name, does not contain a $: " + name);
        }

        String prefix = name.substring(0, pos);
        String localName = name.substring(pos + 1);
        String uri = namespaces.getNamespace(prefix);
        if (uri == null) {
            throw new JsonFormatException("Undefined prefix in qualified name: " + name);
        }

        return new QName(uri, localName);
    }

    public static String toJson(QName qname, Namespaces namespaces) {
        return namespaces.getOrMakePrefix(qname.getNamespace()) + "$" + qname.getName();
    }
}
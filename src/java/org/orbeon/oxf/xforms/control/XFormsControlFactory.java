/**
 *  Copyright (C) 2006 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.control;

import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.xforms.XFormsContainer;
import org.orbeon.oxf.xforms.control.controls.*;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory for all existing XForms controls.
 */
public class XFormsControlFactory {

    private static Map nameToClassMap = new HashMap();

    // TODO: fix terminology which is not consistent with class hierarchy
    private static final Map CONTAINER_CONTROLS = new HashMap();
    private static final Map CORE_VALUE_CONTROLS = new HashMap();
    private static final Map CORE_CONTROLS = new HashMap();
    private static final Map BUILTIN_CONTROLS = new HashMap();

    public static final Map MANDATORY_SINGLE_NODE_CONTROLS = new HashMap();
    public static final Map OPTIONAL_SINGLE_NODE_CONTROLS = new HashMap();
    public static final Map NO_SINGLE_NODE_CONTROLS = new HashMap();
    public static final Map MANDATORY_NODESET_CONTROLS = new HashMap();
    public static final Map NO_NODESET_CONTROLS = new HashMap();
    public static final Map SINGLE_NODE_OR_VALUE_CONTROLS = new HashMap();

    static {
        CONTAINER_CONTROLS.put("group", "");
        CONTAINER_CONTROLS.put("repeat", "");
        CONTAINER_CONTROLS.put("switch", "");
        CONTAINER_CONTROLS.put("case", "");
        CONTAINER_CONTROLS.put("dialog", "");

        CORE_VALUE_CONTROLS.put("input", "");
        CORE_VALUE_CONTROLS.put("secret", "");
        CORE_VALUE_CONTROLS.put("textarea", "");
        CORE_VALUE_CONTROLS.put("output", "");
        CORE_VALUE_CONTROLS.put("upload", "");
        CORE_VALUE_CONTROLS.put("range", "");
        CORE_VALUE_CONTROLS.put("select", "");
        CORE_VALUE_CONTROLS.put("select1", "");

        CORE_VALUE_CONTROLS.put("attribute", ""); // xxforms:attribute extension
        CORE_VALUE_CONTROLS.put("text", "");      // xxforms:text extension

        final Map coreNoValueControls = new HashMap();
        coreNoValueControls.put("submit", "");
        coreNoValueControls.put("trigger", "");

        CORE_CONTROLS.putAll(CORE_VALUE_CONTROLS);
        CORE_CONTROLS.putAll(coreNoValueControls);

        BUILTIN_CONTROLS.putAll(CONTAINER_CONTROLS);
        BUILTIN_CONTROLS.putAll(CORE_CONTROLS);

        MANDATORY_SINGLE_NODE_CONTROLS.putAll(CORE_VALUE_CONTROLS);
        MANDATORY_SINGLE_NODE_CONTROLS.remove("output");
        MANDATORY_SINGLE_NODE_CONTROLS.put("filename", "");
        MANDATORY_SINGLE_NODE_CONTROLS.put("mediatype", "");
        MANDATORY_SINGLE_NODE_CONTROLS.put("setvalue", "");

        SINGLE_NODE_OR_VALUE_CONTROLS.put("output", "");

        OPTIONAL_SINGLE_NODE_CONTROLS.putAll(coreNoValueControls);
        OPTIONAL_SINGLE_NODE_CONTROLS.put("output", "");  // can have @value attribute
        OPTIONAL_SINGLE_NODE_CONTROLS.put("value", "");   // can have inline text
        OPTIONAL_SINGLE_NODE_CONTROLS.put("label", "");   // can have linking or inline text
        OPTIONAL_SINGLE_NODE_CONTROLS.put("help", "");    // can have linking or inline text
        OPTIONAL_SINGLE_NODE_CONTROLS.put("hint", "");    // can have linking or inline text
        OPTIONAL_SINGLE_NODE_CONTROLS.put("alert", "");   // can have linking or inline text
        OPTIONAL_SINGLE_NODE_CONTROLS.put("copy", "");
        OPTIONAL_SINGLE_NODE_CONTROLS.put("load", "");    // can have linking
        OPTIONAL_SINGLE_NODE_CONTROLS.put("message", ""); // can have linking or inline text
        OPTIONAL_SINGLE_NODE_CONTROLS.put("group", "");
        OPTIONAL_SINGLE_NODE_CONTROLS.put("switch", "");

        NO_SINGLE_NODE_CONTROLS.put("choices", "");
        NO_SINGLE_NODE_CONTROLS.put("item", "");
        NO_SINGLE_NODE_CONTROLS.put("case", "");
        NO_SINGLE_NODE_CONTROLS.put("toggle", "");

        MANDATORY_NODESET_CONTROLS.put("repeat", "");
        MANDATORY_NODESET_CONTROLS.put("itemset", "");
        MANDATORY_NODESET_CONTROLS.put("delete", "");

        NO_NODESET_CONTROLS.putAll(MANDATORY_SINGLE_NODE_CONTROLS);
        NO_NODESET_CONTROLS.putAll(OPTIONAL_SINGLE_NODE_CONTROLS);
        NO_NODESET_CONTROLS.putAll(NO_SINGLE_NODE_CONTROLS);
    }

    static {
        // Built-in standard controls
        nameToClassMap.put("case", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsCaseControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("group", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsGroupControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("input", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsInputControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("output", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsOutputControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("range", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsRangeControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("repeat", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsRepeatControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("secret", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsSecretControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("select1", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsSelect1Control(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("select", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsSelectControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("submit", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsSubmitControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("switch", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsSwitchControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("textarea", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsTextareaControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("trigger", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsTriggerControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("upload", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XFormsUploadControl(container, parent, element, name, effectiveId);
            }
        });
        // Built-in extension controls
        nameToClassMap.put("dialog", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XXFormsDialogControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("attribute", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XXFormsAttributeControl(container, parent, element, name, effectiveId);
            }
        });
        nameToClassMap.put("text", new Factory() {
            public XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
                return new XXFormsTextControl(container, parent, element, name, effectiveId);
            }
        });
    }

    /**
     * Create a new XForms control. The control returned may be a built-in standard control, a built-in extension
     * control, or a custom component.
     *
     * @param container             container
     * @param parent                parent control, null if none
     * @param element               element associated with the control
     * @param effectiveId           effective id of the control
     * @return                      control
     */
    public static XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String effectiveId) {

        final String controlName = element.getName();

        // First try built-in controls
        Factory factory = (Factory) nameToClassMap.get(controlName);

        // Then try custom components
        if (factory == null)
            factory = container.getContainingDocument().getStaticState().getComponentFactory(element.getQName());

        if (factory == null)
            throw new OXFException("Invalid control name: " + Dom4jUtils.qNameToexplodedQName(element.getQName()));

        // Create and return the control
        return factory.createXFormsControl(container, parent, element, controlName, effectiveId);
    }

    public static boolean isValueControl(String controlName) {
        return CORE_VALUE_CONTROLS.get(controlName) != null;
    }

    public static boolean isContainerControl(String controlName) {
        return CONTAINER_CONTROLS.get(controlName) != null;
    }

    public static boolean isCoreControl(String controlName) {
        return CORE_CONTROLS.get(controlName) != null;
    }

    public static boolean isBuiltinControl(String controlName) {
        return BUILTIN_CONTROLS.get(controlName) != null;
    }

    public static abstract class Factory {
        public abstract XFormsControl createXFormsControl(XFormsContainer container, XFormsControl parent, Element element, String name, String effectiveId);
    }

    private XFormsControlFactory() {}
}

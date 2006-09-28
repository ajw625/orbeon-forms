/**
 *  Copyright (C) 2005 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.processor;

import org.apache.commons.pool.ObjectPool;
import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.processor.ProcessorInputOutputInfo;
import org.orbeon.oxf.processor.ProcessorOutput;
import org.orbeon.oxf.util.LoggerFactory;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.util.UUIDUtils;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.XFormsValueControl;
import org.orbeon.oxf.xforms.control.controls.*;
import org.orbeon.oxf.xforms.event.XFormsEvents;
import org.orbeon.oxf.xforms.event.events.XXFormsInitializeEvent;
import org.orbeon.oxf.xforms.event.events.XXFormsInitializeStateEvent;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.util.*;

/**
 * The XForms Server processor handles client requests, including events, and either returns an XML
 * response, or returns a response through the ExternalContext.
 */
public class XFormsServer extends ProcessorImpl {

    static public Logger logger = LoggerFactory.createLogger(XFormsServer.class);

    private static final String INPUT_REQUEST = "request";
    //private static final String OUTPUT_RESPONSE = "response"; // optional

    public static final String APPLICATION_STATE_PREFIX = "application:";
    public static final String SESSION_STATE_PREFIX = "session:";

    public static final Map XFORMS_NAMESPACES = new HashMap();

    static {
        XFORMS_NAMESPACES.put(XFormsConstants.XFORMS_SHORT_PREFIX, XFormsConstants.XFORMS_NAMESPACE_URI);
        XFORMS_NAMESPACES.put(XFormsConstants.XML_EVENTS_PREFIX, XFormsConstants.XML_EVENTS_NAMESPACE_URI);
        XFORMS_NAMESPACES.put(XFormsConstants.XXFORMS_SHORT_PREFIX, XFormsConstants.XXFORMS_NAMESPACE_URI);
    }

    public XFormsServer() {
        addInputInfo(new ProcessorInputOutputInfo(INPUT_REQUEST)); // optional
        //addOutputInfo(new ProcessorInputOutputInfo(OUTPUT_RESPONSE)); // optional
    }

    /**
     * Case where an XML response must be generated.
     */
    public ProcessorOutput createOutput(final String outputName) {
        ProcessorOutput output = new ProcessorImpl.CacheableTransformerOutputImpl(getClass(), outputName) {
            public void readImpl(final PipelineContext pipelineContext, ContentHandler contentHandler) {
                doIt(pipelineContext, contentHandler);
            }
        };
        addOutput(outputName, output);
        return output;
    }

    /**
     * Case where the response is generated throug the ExternalContext.
     */
    public void start(PipelineContext pipelineContext) {
        doIt(pipelineContext, null);
    }

    private void doIt(final PipelineContext pipelineContext, ContentHandler contentHandler) {

        final ExternalContext externalContext = (ExternalContext) pipelineContext.getAttribute(PipelineContext.EXTERNAL_CONTEXT);

        final Element filesElement;
        final Element actionElement;
        final XFormsContainingDocument containingDocument;
        final XFormsState xformsState;
        final String staticStateUUID;

        // Use request input provided by client
        final Document requestDocument = readInputAsDOM4J(pipelineContext, INPUT_REQUEST);

        // Get action
        actionElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_ACTION_QNAME);

        // Get files if any (those come from xforms-server-submit.xpl upon submission)
        filesElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_FILES_QNAME);

        // Retrieve state
        {
            // Get static state
            final String staticStateString;
            {
                final Element staticStateElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_STATIC_STATE_QNAME);
                staticStateString = staticStateElement.getTextTrim();
            }

            // Get dynamic state
            final String dynamicStateString;
            {
                final Element dynamicStateElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_DYNAMIC_STATE_QNAME);
                dynamicStateString = dynamicStateElement.getTextTrim();
            }
            
            if (dynamicStateString.startsWith(APPLICATION_STATE_PREFIX)) {
                //  State is currently stored in the application scope
                final String dynamicStateUUID = dynamicStateString.substring(APPLICATION_STATE_PREFIX.length());

                // Extract page generation id
                staticStateUUID = staticStateString.substring(APPLICATION_STATE_PREFIX.length());

                // We don't create the session cache at this point as it may not be necessary
                final XFormsServerApplicationStateCache applicationStateCache = XFormsServerApplicationStateCache.instance(externalContext, false);
                final XFormsState applicationFormsState = applicationStateCache.find(staticStateUUID, dynamicStateUUID);

                // This is not going to be good when it happens, and we must create a caching heuristic that minimizes this
                if (applicationFormsState == null)
                    throw new OXFException("Unable to retrieve XForms engine state from application cache.");

                xformsState = applicationFormsState;

            } else if (dynamicStateString.startsWith(SESSION_STATE_PREFIX)) {
                // State doesn't come with the request, we should look it up in the repository
                final String dynamicStateUUID = dynamicStateString.substring(SESSION_STATE_PREFIX.length());

                // Extract page generation id
                staticStateUUID = staticStateString.startsWith(SESSION_STATE_PREFIX)
                        ? staticStateString.substring(SESSION_STATE_PREFIX.length())
                        : staticStateString.substring(APPLICATION_STATE_PREFIX.length());

                // We don't create the session cache at this point as it may not be necessary
                final XFormsServerSessionStateCache sessionStateCache = XFormsServerSessionStateCache.instance(externalContext.getSession(false), false);
                final XFormsState sessionFormsState = (sessionStateCache == null) ? null : sessionStateCache.find(staticStateUUID, dynamicStateUUID);

                // This is not going to be good when it happens, and we must create a caching heuristic that minimizes this
                if (sessionFormsState == null)
                    throw new OXFException("Unable to retrieve XForms engine state from session cache.");

                xformsState = sessionFormsState;
            } else {
                // State comes with request
                staticStateUUID = null;
                xformsState = new XFormsState(staticStateString, dynamicStateString);
            }
        }

        if (XFormsUtils.isCacheDocument()) {
            // Try to obtain containing document from cache
            if (filesElement == null) {
                // No fileElements, this may have been cached
                containingDocument = XFormsServerDocumentCache.instance().find(pipelineContext, xformsState);
            } else  {
                // If there are filesElement, then we know this was not cached
                logger.debug("XForms - containing document cache (getContainingDocument): fileElements present.");
                containingDocument = createXFormsContainingDocument(pipelineContext, xformsState, filesElement);
            }
        } else {
            // Otherwise we recreate the containing document from scratch
            containingDocument = createXFormsContainingDocument(pipelineContext, xformsState, filesElement);
        }

        try {
            // Run event if any
            boolean allEvents = false;
            final Map valueChangeControlIds = new HashMap();
            if (actionElement != null) {
                final List eventElements = actionElement.elements(XFormsConstants.XXFORMS_EVENT_QNAME);
                if (eventElements != null && eventElements.size() > 0) {
                    // NOTE: We store here the last xxforms-value-change-with-focus-change event so
                    // we can coalesce values in case several such events are sent for the same
                    // control. The client should not send us such series of events, but currently
                    // it may happen.
                    String lastSourceControlId = null;
                    String lastValueChangeEventValue = null;

                    containingDocument.prepareForExternalEventsSequence(pipelineContext);

                    for (Iterator i = eventElements.iterator(); i.hasNext();) {
                        final Element eventElement = (Element) i.next();
                        final String sourceControlId = eventElement.attributeValue("source-control-id");
                        final String otherControlId = eventElement.attributeValue("other-control-id");
                        final String eventName = eventElement.attributeValue("name");
                        final String value = eventElement.getText();

                        if (XFormsEvents.XXFORMS_ALL_EVENTS_REQUIRED.equals(eventName)) {
                            // Special event telling us to resend the client all the events since initialization
                            allEvents = true;
                        } else if (sourceControlId != null && eventName != null) {
                            // An event is passed
                            if (eventName.equals(XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE) && otherControlId == null) {
                                // xxforms-value-change-with-focus-change event
                                if (lastSourceControlId == null) {
                                    // Rember event
                                    lastSourceControlId = sourceControlId;
                                    lastValueChangeEventValue = value;
                                } else if (lastSourceControlId.equals(sourceControlId)) {
                                    // Update event
                                    lastValueChangeEventValue = value;
                                } else {
                                    // Send old event
                                    executeExternalEventPrepareIfNecessary(pipelineContext, containingDocument, XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE, lastSourceControlId, null, lastValueChangeEventValue);
                                    // Remember new event
                                    lastSourceControlId = sourceControlId;
                                    lastValueChangeEventValue = value;
                                }
                            } else {
                                if (lastSourceControlId != null) {
                                    // Send old event
                                    executeExternalEventPrepareIfNecessary(pipelineContext, containingDocument, XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE, lastSourceControlId, null, lastValueChangeEventValue);
                                    lastSourceControlId = null;
                                    lastValueChangeEventValue = null;
                                }
                                // Send new event
                                executeExternalEventPrepareIfNecessary(pipelineContext, containingDocument, eventName, sourceControlId, otherControlId, value);
                            }

                            if (eventName.equals(XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE)) {
                                // Remember id of control of which value changed
                                valueChangeControlIds.put(sourceControlId, "");
                            }
                        } else if (!(sourceControlId == null && eventName == null)) {
                            throw new OXFException("<event> element must either have source-control-id and name attributes, or no attribute.");
                        }
                    }
                    // Flush stored event if needed
                    if (lastSourceControlId != null) {
                        // Send old event
                        executeExternalEventPrepareIfNecessary(pipelineContext, containingDocument, XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE, lastSourceControlId, null, lastValueChangeEventValue);
                    }
                }
            }

            // Create resulting document if there is a ContentHandler
            if (contentHandler != null) {
                outputResponse(containingDocument, allEvents, valueChangeControlIds, pipelineContext, contentHandler,
                        staticStateUUID, externalContext, xformsState);
            }
        } catch (Throwable e) {
            // If an exception is caught, we need to discard the object as its state may be inconsistent
            final ObjectPool sourceObjectPool = containingDocument.getSourceObjectPool();
            if (sourceObjectPool != null) {
                logger.debug("XForms - containing document cache: throwable caught, discarding document from pool.");
                try {
                    sourceObjectPool.invalidateObject(containingDocument);
                    containingDocument.setSourceObjectPool(null);
                } catch (Exception e1) {
                    throw new OXFException(e1);
                }
            }
            throw new OXFException(e);
        }
    }

    /**
     * Execute an external event while preparing containing document and controls state if an event
     * was already executed.
     */
    private void executeExternalEventPrepareIfNecessary(PipelineContext pipelineContext, XFormsContainingDocument containingDocument, String eventName, String controlId, String otherControlId, String contextString) {
        containingDocument.startOutermostActionHandler();
        containingDocument.executeExternalEvent(pipelineContext, eventName, controlId, otherControlId, contextString, null);
        containingDocument.endOutermostActionHandler(pipelineContext);
    }

    private void outputResponse(XFormsContainingDocument containingDocument, boolean allEvents, Map valueChangeControlIds,
                                PipelineContext pipelineContext, ContentHandler contentHandler, String requestPageGenerationId,
                                ExternalContext externalContext, XFormsState xformsState) {

        final XFormsControls xformsControls = containingDocument.getXFormsControls();

        try {
            final ContentHandlerHelper ch = new ContentHandlerHelper(contentHandler);
            ch.startDocument();
            contentHandler.startPrefixMapping("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI);
            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "event-response");

            // Output state
            boolean[] requireClientSubmission = new boolean[1];
            {
                // Produce static state if needed
                final String currentPageGenerationId = (requestPageGenerationId != null) ? requestPageGenerationId : UUIDUtils.createPseudoUUID();

                // Create and encode dynamic state
                final String newEncodedDynamicState;
                {
                    final Document dynamicStateDocument = createDynamicStateDocument(containingDocument, requireClientSubmission);
                    newEncodedDynamicState = XFormsUtils.encodeXML(pipelineContext, dynamicStateDocument,
                            containingDocument.isSessionStateHandling() ? null : XFormsUtils.getEncryptionKey());
                }
                final XFormsState newXFormsState = new XFormsState(xformsState.getStaticState(), newEncodedDynamicState);

                ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "dynamic-state");
                if (containingDocument.isSessionStateHandling()) {
                    // Produce dynamic state key
                    final String newRequestId = UUIDUtils.createPseudoUUID();
                    final XFormsServerSessionStateCache sessionStateCache = XFormsServerSessionStateCache.instance(externalContext.getSession(true), true);
                    sessionStateCache.add(currentPageGenerationId, newRequestId, newXFormsState);
                    ch.text(SESSION_STATE_PREFIX + newRequestId);
                } else {
                    // Send state to the client
                    ch.text(newEncodedDynamicState);
                }
                ch.endElement();

                // Cache document if requested and possible
                if (XFormsUtils.isCacheDocument()) {
                    if (!requireClientSubmission[0]) {
                        // NOTE: We check on requireClientSubmission because the event is encoded
                        // in the dynamic state. But if we stored the event separately, then we
                        // could still cache the containing document.
                        XFormsServerDocumentCache.instance().add(pipelineContext, newXFormsState, containingDocument);
                    } else {
                        // Since we cannot cache the result, we have to get the object out of its current pool
                        final ObjectPool objectPool = containingDocument.getSourceObjectPool();
                        if (objectPool != null) {
                            logger.debug("XForms - containing document cache: discarding non-cacheable document from pool.");
                            try {
                                objectPool.invalidateObject(containingDocument);
                                containingDocument.setSourceObjectPool(null);
                            } catch (Exception e1) {
                                throw new OXFException(e1);
                            }
                        }
                    }
                }
            }

            // Output action
            {
                final XFormsContainingDocument initialContainingDocument;
                if (!allEvents) {
                    initialContainingDocument = null;
                } else {
                    // TODO: use cached static state if possible
                    initialContainingDocument = createXFormsContainingDocument(pipelineContext, new XFormsState(xformsState.getStaticState(), null), null);
                    initialContainingDocument.getXFormsControls().rebuildCurrentControlsStateIfNeeded(pipelineContext);
                }

                ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "action");

                // Output new controls values and associated information
                final Map itemsetsFull1 = new HashMap();
                final Map itemsetsFull2 = new HashMap();
                {
                    ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "control-values");

                    if (!allEvents) {
                        // Common case

                        if (xformsControls.isDirty()) {
                            // Only output changes if needed
                            xformsControls.rebuildCurrentControlsStateIfNeeded(pipelineContext);
                            final XFormsControls.ControlsState currentControlsState = xformsControls.getCurrentControlsState();

                            diffControlsState(ch, containingDocument,  xformsControls.getInitialControlsState().getChildren(), currentControlsState.getChildren(), itemsetsFull1, itemsetsFull2, valueChangeControlIds);
                        }
                    } else {
                        // Reload / back case
                        xformsControls.rebuildCurrentControlsStateIfNeeded(pipelineContext);
                        final XFormsControls.ControlsState currentControlsState = xformsControls.getCurrentControlsState();
                        final XFormsControls.ControlsState initialControlsState = initialContainingDocument.getXFormsControls().getCurrentControlsState();

                        // Output diffs
                        diffControlsState(ch, containingDocument, initialControlsState.getChildren(), currentControlsState.getChildren(), itemsetsFull1, itemsetsFull2, null);
                    }

                    ch.endElement();
                }

                // Output divs information
                {
                    if (!allEvents) {
                        diffSwitchDivs(ch, xformsControls, xformsControls.getInitialSwitchState(), xformsControls.getCurrentSwitchState());
                    } else {
                        diffSwitchDivs(ch, xformsControls, initialContainingDocument.getXFormsControls().getCurrentSwitchState(), xformsControls.getCurrentSwitchState());
                    }
                }

                // Output repeats information
                {
                    // Output index updates
                    // TODO: move index state out of ControlsState + handle diffs

                    if (!allEvents) {
                        diffIndexState(ch, xformsControls.getInitialControlsState().getRepeatIdToIndex(), xformsControls.getCurrentControlsState().getRepeatIdToIndex());
                    } else {
                        final XFormsControls.ControlsState currentControlsState = xformsControls.getCurrentControlsState();
                        final XFormsControls.ControlsState initialControlsState = initialContainingDocument.getXFormsControls().getCurrentControlsState();
                        diffIndexState(ch, initialControlsState.getRepeatIdToIndex(), currentControlsState.getRepeatIdToIndex());
                    }
                }

                // Output itemset information
                {
                    // Diff itemset information
                    final Map itemsetUpdate = diffItemsets(itemsetsFull1, itemsetsFull2);
                    // TODO: handle allEvents case
                    outputItemsets(ch, itemsetUpdate);
                }

                // Check if we want to require the client to perform a form submission
                {
                    if (requireClientSubmission[0])
                        outputSubmissionInfo(externalContext, ch);
                }

                // TODO: the following should be correctly ordered in the order they were requested
                // Output messages to display
                {
                    final List messages = containingDocument.getMessagesToRun();
                    if (messages != null) {
                        outputMessagesInfo(ch, messages);
                    }
                }

                // Output loads
                {
                    final List loads = containingDocument.getLoadsToRun();
                    if (loads != null) {
                        outputLoadsInfo(ch, loads);
                    }
                }

                // Output scripts
                {
                    final List scripts = containingDocument.getScriptsToRun();
                    if (scripts != null) {
                        outputScriptsInfo(ch, scripts);
                    }
                }

                // Output focus instructions
                {
                    final String focusEffectiveControlId = containingDocument.getClientFocusEffectiveControlId(pipelineContext);
                    if (focusEffectiveControlId != null) {
                        outputFocusInfo(ch, focusEffectiveControlId);
                    }
                }

                ch.endElement();
            }

            ch.endElement();
            contentHandler.endPrefixMapping("xxf");
            ch.endDocument();
        } catch (SAXException e) {
            throw new OXFException(e);
        }
    }

    private void diffIndexState(ContentHandlerHelper ch, Map initialRepeatIdToIndex, Map currentRepeatIdToIndex) {
        if (currentRepeatIdToIndex.size() != 0) {
            boolean found = false;
            for (Iterator i = initialRepeatIdToIndex.entrySet().iterator(); i.hasNext();) {
                final Map.Entry currentEntry = (Map.Entry) i.next();
                final String repeatId = (String) currentEntry.getKey();
                final Integer index = (Integer) currentEntry.getValue();

                // Output information if there is a difference
                final Integer newIndex = (Integer) currentRepeatIdToIndex.get(repeatId);
                if (!index.equals(newIndex)) {

                    if (!found) {
                        ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "repeat-indexes");
                        found = true;
                    }

                    ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "repeat-index",
                            new String[] {"id", repeatId, "old-index", index.toString(), "new-index", newIndex.toString()});
                }
            }
            if (found)
                ch.endElement();
        }
    }

    public static Map diffItemsets(Map itemsetsFull1, Map itemsetsFull2) {
        Map itemsetUpdate;
        if (itemsetsFull2 == null) {
            // There is no update in the first place
            itemsetUpdate = null;
        } else if (itemsetsFull1 == null) {
            // There was nothing before, return update
            itemsetUpdate = itemsetsFull2;
        } else {
            // Merge differences
            itemsetUpdate = new HashMap();

            for (Iterator i = itemsetsFull2.entrySet().iterator(); i.hasNext();) {
                final Map.Entry currentEntry = (Map.Entry) i.next();
                final String itemsetId = (String) currentEntry.getKey();
                final List newItems = (List) currentEntry.getValue();

                final List existingItems = (List) itemsetsFull1.get(itemsetId);
                if (existingItems == null || !existingItems.equals(newItems)) {
                    // No existing items or new items are different from existing items
                    itemsetUpdate.put(itemsetId, newItems);
                }
            }
        }
        return itemsetUpdate;
    }

    public static Document createDynamicStateDocument(XFormsContainingDocument containingDocument, boolean[] requireClientSubmission) {

        final XFormsControls.ControlsState currentControlsState = containingDocument.getXFormsControls().getCurrentControlsState();

        final Document dynamicStateDocument = Dom4jUtils.createDocument();
        final Element dynamicStateElement = dynamicStateDocument.addElement("dynamic-state");
        // Output updated instances
        {
            final Element instancesElement = dynamicStateElement.addElement("instances");

            for (Iterator i = containingDocument.getModels().iterator(); i.hasNext();) {
                final XFormsModel currentModel = (XFormsModel) i.next();

                for (Iterator j = currentModel.getInstances().iterator(); j.hasNext();) {
                    final XFormsInstance currentInstance = (XFormsInstance) j.next();
//                    if (currentInstance.isReadOnly())
//                        xxx;


                    instancesElement.add((currentInstance).getInstanceDocument().getRootElement().createCopy());
                    // Log instance if needed
                    if (logger.isDebugEnabled()) {
                        logger.debug("XForms - resulting instance: model id='" + currentModel.getEffectiveId() +  "', instance id= '" + currentInstance.getEffectiveId() + "'\n"
                                + Dom4jUtils.domToString(currentInstance.getInstanceDocument()));
                    }
                }
            }
        }

        // Output divs information
        {
            final Element divsElement = dynamicStateElement.addElement("divs");
            outputSwitchDivs(divsElement, containingDocument.getXFormsControls());
        }

        // Output repeat index information
        {
            final Map repeatIdToIndex = currentControlsState.getRepeatIdToIndex();
            if (repeatIdToIndex.size() != 0) {
                final Element repeatIndexesElement = dynamicStateElement.addElement("repeat-indexes");
                for (Iterator i = repeatIdToIndex.entrySet().iterator(); i.hasNext();) {
                    final Map.Entry currentEntry = (Map.Entry) i.next();
                    final String repeatId = (String) currentEntry.getKey();
                    final Integer index = (Integer) currentEntry.getValue();
                    final Element newElement = repeatIndexesElement.addElement("repeat-index");
                    newElement.addAttribute("id", repeatId);
                    newElement.addAttribute("index", index.toString());
                }
            }
        }

        // Submission automatic event if needed
        {
            // Check for xxforms-submit event
            {
                final XFormsModelSubmission activeSubmission = containingDocument.getActiveSubmission();
                if (activeSubmission != null) {
                    final Element eventElement = dynamicStateElement.addElement("event");
                    eventElement.addAttribute("source-control-id", activeSubmission.getEffectiveId());
                    eventElement.addAttribute("name", XFormsEvents.XXFORMS_SUBMIT);
                    requireClientSubmission[0] = true;
                }
            }
            // Check for xxforms-load event
            {
                final List loads = containingDocument.getLoadsToRun();
                if (loads != null) {
                    for (Iterator i = loads.iterator(); i.hasNext();) {
                        final XFormsContainingDocument.Load load = (XFormsContainingDocument.Load) i.next();

                        if (load.isReplace() && load.isPortletLoad() && !NetUtils.urlHasProtocol(load.getResource()) && !"resource".equals(load.getUrlType())) {
                            // We need to submit the event so that the portlet can load the new path
                            final Element eventElement = dynamicStateElement.addElement("event");
                            eventElement.addAttribute("source-control-id", XFormsContainingDocument.CONTAINING_DOCUMENT_PSEUDO_ID);
                            eventElement.addAttribute("resource", load.getResource());
                            // NOTE: don't care about the target for portlets
                            eventElement.addAttribute("name", XFormsEvents.XXFORMS_LOAD);
                            requireClientSubmission[0] = true;

                            break;
                        }
                    }
                }
            }
        }
        return dynamicStateDocument;
    }

    public static void diffControlsState(ContentHandlerHelper ch, XFormsContainingDocument containingDocument, List state1, List state2, Map itemsetsFull1, Map itemsetsFull2, Map valueChangeControlIds) {

        // Trivial case
        if (state1 == null && state2 == null)
            return;

        // Both lists must have the same size if present; state1 can be null
        if ((state1 != null && state2 != null && state1.size() != state2.size()) || (state2 == null)) {
            throw new IllegalStateException("Illegal state when comparing controls.");
        }

        final boolean isStaticReadonly = containingDocument.getReadonlyAppearance().equals(XFormsConstants.XXFORMS_READONLY_APPEARANCE_STATIC_VALUE);
        final AttributesImpl attributesImpl = new AttributesImpl();
        final Iterator j = (state1 == null) ? null : state1.iterator();
        for (Iterator i = state2.iterator(); i.hasNext();) {
            final XFormsControl xformsControl1 = (state1 == null) ? null : (XFormsControl) j.next();
            final XFormsControl xformsControl2 = (XFormsControl) i.next();

            // 1: Check current control
            if (!(xformsControl2 instanceof XFormsRepeatControl)) {
                // xforms:repeat doesn't need to be handled independently, iterations do it

                // Output diffs between controlInfo1 and controlInfo2

                final boolean isValueChangeControl = valueChangeControlIds != null && valueChangeControlIds.get(xformsControl2.getEffectiveId()) != null;
                if ((!xformsControl2.equals(xformsControl1) || isValueChangeControl) && !(isStaticReadonly && xformsControl2.isReadonly() && xformsControl2 instanceof XFormsTriggerControl)) {
                    // Don't send anything if nothing has changed
                    // But we force a change for controls whose values changed in the request
                    // Also, we don't output anything for triggers in static readonly mode

                    attributesImpl.clear();

                    // Control id
                    attributesImpl.addAttribute("", "id", "id", ContentHandlerHelper.CDATA, xformsControl2.getEffectiveId());

                    // Control children values
                    if (!(xformsControl2 instanceof RepeatIterationControl)) {
                        {
                            final String labelValue1 = (xformsControl1 == null) ? null : xformsControl1.getLabel();
                            final String labelValue2 = xformsControl2.getLabel();

                            if (!((labelValue1 == null && labelValue2 == null) || (labelValue1 != null && labelValue2 != null && labelValue1.equals(labelValue2)))) {
                                attributesImpl.addAttribute("", "label", "label", ContentHandlerHelper.CDATA, labelValue2 != null ? labelValue2 : "");
                            }
                        }

                        {
                            final String helpValue1 = (xformsControl1 == null) ? null : xformsControl1.getHelp();
                            final String helpValue2 = xformsControl2.getHelp();

                            if (!((helpValue1 == null && helpValue2 == null) || (helpValue1 != null && helpValue2 != null && helpValue1.equals(helpValue2)))) {
                                attributesImpl.addAttribute("", "help", "help", ContentHandlerHelper.CDATA, helpValue2 != null ? helpValue2 : "");
                            }
                        }

                        {
                            final String hintValue1 = (xformsControl1 == null) ? null : xformsControl1.getHint();
                            final String hintValue2 = xformsControl2.getHint();

                            if (!((hintValue1 == null && hintValue2 == null) || (hintValue1 != null && hintValue2 != null && hintValue1.equals(hintValue2)))) {
                                attributesImpl.addAttribute("", "hint", "hint", ContentHandlerHelper.CDATA, hintValue2 != null ? hintValue2 : "");
                            }
                        }

                        {
                            final String alertValue1 = (xformsControl1 == null) ? null : xformsControl1.getAlert();
                            final String alertValue2 = xformsControl2.getAlert();

                            if (!((alertValue1 == null && alertValue2 == null) || (alertValue1 != null && alertValue2 != null && alertValue1.equals(alertValue2)))) {
                                attributesImpl.addAttribute("", "alert", "alert", ContentHandlerHelper.CDATA, alertValue2 != null ? alertValue2 : "");
                            }
                        }

                        // Output xforms:output-specific information
                        if (xformsControl2 instanceof XFormsOutputControl) {
                            final XFormsOutputControl outputControlInfo1 = (XFormsOutputControl) xformsControl1;
                            final XFormsOutputControl outputControlInfo2 = (XFormsOutputControl) xformsControl2;

                            final String mediaTypeValue1 = (outputControlInfo1 == null) ? null : outputControlInfo1.getMediaTypeAttribute();
                            final String mediaTypeValue2 = outputControlInfo2.getMediaTypeAttribute();

                            if (!((mediaTypeValue1 == null && mediaTypeValue2 == null) || (mediaTypeValue1 != null && mediaTypeValue2 != null && mediaTypeValue1.equals(mediaTypeValue2)))) {
                                attributesImpl.addAttribute("", "mediatype", "mediatype", ContentHandlerHelper.CDATA, mediaTypeValue2 != null ? mediaTypeValue2 : "");
                            }
                        }
                    }

                    // Model item properties
                    if (xformsControl1 == null || xformsControl1.isReadonly() != xformsControl2.isReadonly()) {
                        attributesImpl.addAttribute("", XFormsConstants.XXFORMS_READONLY_ATTRIBUTE_NAME,
                                XFormsConstants.XXFORMS_READONLY_ATTRIBUTE_NAME,
                                ContentHandlerHelper.CDATA, Boolean.toString(xformsControl2.isReadonly()));
                    }
                    if (xformsControl1 == null || xformsControl1.isRequired() != xformsControl2.isRequired()) {
                        attributesImpl.addAttribute("", XFormsConstants.XXFORMS_REQUIRED_ATTRIBUTE_NAME,
                                XFormsConstants.XXFORMS_REQUIRED_ATTRIBUTE_NAME,
                                ContentHandlerHelper.CDATA, Boolean.toString(xformsControl2.isRequired()));
                    }
                    if (xformsControl1 == null || xformsControl1.isRelevant() != xformsControl2.isRelevant()) {
                        attributesImpl.addAttribute("", XFormsConstants.XXFORMS_RELEVANT_ATTRIBUTE_NAME,
                                XFormsConstants.XXFORMS_RELEVANT_ATTRIBUTE_NAME,
                                ContentHandlerHelper.CDATA, Boolean.toString(xformsControl2.isRelevant()));
                    }
                    if (xformsControl1 == null || xformsControl1.isValid() != xformsControl2.isValid()) {
                        attributesImpl.addAttribute("", XFormsConstants.XXFORMS_VALID_ATTRIBUTE_NAME,
                                XFormsConstants.XXFORMS_VALID_ATTRIBUTE_NAME,
                                ContentHandlerHelper.CDATA, Boolean.toString(xformsControl2.isValid()));
                    }

                    final boolean isOutputControlWithValueAttribute = xformsControl2 instanceof XFormsOutputControl && ((XFormsOutputControl) xformsControl2).getValueAttribute() != null;
                    if (!(xformsControl2 instanceof RepeatIterationControl) && !isOutputControlWithValueAttribute) {

                        final String typeValue1 = (xformsControl1 == null) ? null : xformsControl1.getType();
                        final String typeValue2 = xformsControl2.getType();

                        if (xformsControl1 == null || !((typeValue1 == null && typeValue2 == null) || (typeValue1 != null && typeValue2 != null && typeValue1.equals(typeValue2)))) {
                            attributesImpl.addAttribute("", XFormsConstants.XXFORMS_TYPE_ATTRIBUTE_NAME, XFormsConstants.XXFORMS_TYPE_ATTRIBUTE_NAME,
                                ContentHandlerHelper.CDATA, typeValue2 != null ? typeValue2 : "");
                        }
                    }

                    if (!(xformsControl2 instanceof RepeatIterationControl)) {
                        // Regular control

                        // Get current value if possible for this control
                        // NOTE: We issue the new value in all cases because we don't have yet a mechanism to tell the
                        // client not to update the value, unlike with attributes which can be missing
                        if (xformsControl2 instanceof XFormsValueControl && !(xformsControl2 instanceof XFormsUploadControl)) {

                            final XFormsValueControl xformsValueControl = (XFormsValueControl) xformsControl2;

                            // Check if a "display-value" attribute must be added
                            if (!isOutputControlWithValueAttribute) {
                                final String displayValue = xformsValueControl.getDisplayValue();
                                if (displayValue != null)
                                    attributesImpl.addAttribute("", "display-value", "display-value", ContentHandlerHelper.CDATA, displayValue);
                            }

                            // Create element with text value
                            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "control", attributesImpl);
                            ch.text(xformsValueControl.convertToExternalValue(xformsValueControl.getValue()));
                            ch.endElement();
                        } else if (!"case".equals(xformsControl2.getName())) {
                            // No value, just output element with no content
                            ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "control", attributesImpl);
                        }
                    } else {
                        // Repeat iteration
                        final RepeatIterationControl repeatIterationInfo = (RepeatIterationControl) xformsControl2;
                        attributesImpl.addAttribute("", "iteration", "iteration", ContentHandlerHelper.CDATA, Integer.toString(repeatIterationInfo.getIteration()));

                        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "repeat-iteration", attributesImpl);
                    }
                }

                // Handle itemsets
                if (xformsControl2 instanceof XFormsSelect1Control || xformsControl2 instanceof XFormsSelectControl) {
                    final XFormsSelect1Control xformsSelect1Control1 = (XFormsSelect1Control) xformsControl1;
                    final XFormsSelect1Control xformsSelect1Control2 = (XFormsSelect1Control) xformsControl2;

                    if (itemsetsFull1 != null && xformsSelect1Control1 != null) {
                        final Object items = xformsSelect1Control1.getItemset();
                        if (items != null)
                            itemsetsFull1.put(xformsSelect1Control1.getEffectiveId(), items);
                    }

                    if (itemsetsFull2 != null && xformsSelect1Control2 != null) {
                        final Object items = xformsSelect1Control2.getItemset();
                        if (items != null)
                            itemsetsFull2.put(xformsSelect1Control2.getEffectiveId(), items);
                    }
                }
            }

            // 2: Check children if any
            if (XFormsControls.isGroupingControl(xformsControl2.getName()) || xformsControl2 instanceof RepeatIterationControl) {

                final List children1 = (xformsControl1 == null) ? null : xformsControl1.getChildren();
                final List children2 = (xformsControl2.getChildren() == null) ? Collections.EMPTY_LIST : xformsControl2.getChildren();

                // Repeat grouping control
                if (xformsControl2 instanceof XFormsRepeatControl && children1 != null) {

                    final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) xformsControl2;

                    // Special case of repeat update

                    final int size1 = children1.size();
                    final int size2 = children2.size();

                    if (size1 == size2) {
                        // No add or remove of children
                        diffControlsState(ch, containingDocument, children1, xformsControl2.getChildren(), itemsetsFull1, itemsetsFull2, valueChangeControlIds);
                    } else if (size2 > size1) {
                        // Size has grown

                        // Copy template instructions
                        for (int k = size1 + 1; k <= size2; k++) {
                            outputCopyRepeatTemplate(ch, repeatControlInfo, k);
                        }

                        // Diff the common subset
                        diffControlsState(ch, containingDocument, children1, children2.subList(0, size1), itemsetsFull1, itemsetsFull2, valueChangeControlIds);

                        // Issue new values for new iterations
                        diffControlsState(ch, containingDocument, null, children2.subList(size1, size2), itemsetsFull1, itemsetsFull2, valueChangeControlIds);

                    } else if (size2 < size1) {
                        // Size has shrunk

                        final String repeatControlId = xformsControl2.getEffectiveId();
                        final int indexOfRepeatHierarchySeparator = repeatControlId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
                        final String templateId = (indexOfRepeatHierarchySeparator == -1) ? repeatControlId : repeatControlId.substring(0, indexOfRepeatHierarchySeparator);
                        final String parentIndexes = (indexOfRepeatHierarchySeparator == -1) ? "" : repeatControlId.substring(indexOfRepeatHierarchySeparator + 1);

                        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "delete-repeat-elements",
                                new String[]{"id", templateId, "parent-indexes", parentIndexes, "count", "" + (size1 - size2)});

                        // Diff the remaining subset
                        diffControlsState(ch, containingDocument, children1.subList(0, size2), children2, itemsetsFull1, itemsetsFull2, valueChangeControlIds);
                    }
                } else if (xformsControl2 instanceof XFormsRepeatControl && xformsControl1 == null) {

                    final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) xformsControl2;

                    // Handle new sub-xforms:repeat

                    // Copy template instructions
                    final int size2 = children2.size();
                    for (int k = 2; k <= size2; k++) { // don't copy the first template, which is already copied when the parent is copied
                        outputCopyRepeatTemplate(ch, repeatControlInfo, k);
                    }

                    // Issue new values for the children
                    diffControlsState(ch, containingDocument, null, children2, itemsetsFull1, itemsetsFull2, valueChangeControlIds);

                } else if (xformsControl2 instanceof XFormsRepeatControl && children1 == null) {

                    final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) xformsControl2;

                    // Handle repeat growing from size 0 (case of instance replacement, for example)

                    // Copy template instructions
                    final int size2 = children2.size();
                    for (int k = 1; k <= size2; k++) {
                        outputCopyRepeatTemplate(ch, repeatControlInfo, k);
                    }

                    // Issue new values for the children
                    diffControlsState(ch, containingDocument, null, children2, itemsetsFull1, itemsetsFull2, valueChangeControlIds);
                } else {
                    // Other grouping controls
                    diffControlsState(ch, containingDocument, children1, children2, itemsetsFull1, itemsetsFull2, valueChangeControlIds);
                }
            }
        }
    }

    private static void outputCopyRepeatTemplate(ContentHandlerHelper ch, XFormsRepeatControl repeatControlInfo, int idSuffix) {

        final String repeatControlId = repeatControlInfo.getEffectiveId();
        final int indexOfRepeatHierarchySeparator = repeatControlId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        final String parentIndexes = (indexOfRepeatHierarchySeparator == -1) ? "" : repeatControlId.substring(indexOfRepeatHierarchySeparator + 1);

        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "copy-repeat-template",
                new String[]{"id", repeatControlInfo.getRepeatId(), "parent-indexes", parentIndexes,  "id-suffix", Integer.toString(idSuffix) });
    }

    private void outputSubmissionInfo(ExternalContext externalContext, ContentHandlerHelper ch) {
        final String requestURL = externalContext.getRequest().getRequestURL();
        // Signal that we want a POST to the XForms Server
        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "submission",
                new String[]{"action", requestURL, "method", "POST"});
    }

    private void outputMessagesInfo(ContentHandlerHelper ch, List messages) {
        for (Iterator i = messages.iterator(); i.hasNext();) {
            final XFormsContainingDocument.Message message = (XFormsContainingDocument.Message) i.next();
            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "message",
                    new String[]{"level", message.getLevel()});
            ch.text(message.getMessage());
            ch.endElement();
        }
    }

    public static void outputLoadsInfo(ContentHandlerHelper ch, List loads) {
        for (Iterator i = loads.iterator(); i.hasNext();) {
            final XFormsContainingDocument.Load load = (XFormsContainingDocument.Load) i.next();

            if (!(load.isReplace() && load.isPortletLoad() && !NetUtils.urlHasProtocol(load.getResource()) && !"resource".equals(load.getUrlType()))) {
                ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "load",
                        new String[]{ "resource", load.getResource(), (load.getTarget() != null) ? "target" : null, load.getTarget(), "show", load.isReplace() ? "replace" : "new" });
            }
        }
    }

    public static void outputScriptsInfo(ContentHandlerHelper ch, List scripts) {
        for (Iterator i = scripts.iterator(); i.hasNext();) {
            final XFormsContainingDocument.Script script = (XFormsContainingDocument.Script) i.next();
            ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "script",
                    new String[]{ "name", script.getFunctionName(), "target-id", script.getEventTargetId(), "observer-id", script.getEventHandlerContainerId() });
        }
    }

    private void outputFocusInfo(ContentHandlerHelper ch, String focusEffectiveControlId) {
        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "setfocus",
                new String[]{"control-id", focusEffectiveControlId});
    }

    private void outputItemsets(ContentHandlerHelper ch, Map itemsetIdToItemsetInfoMap) {
        if (itemsetIdToItemsetInfoMap != null && itemsetIdToItemsetInfoMap.size() > 0) {
            // There are some xforms:itemset controls

            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "itemsets");
            for (Iterator i = itemsetIdToItemsetInfoMap.entrySet().iterator(); i.hasNext();) {
                final Map.Entry currentEntry = (Map.Entry) i.next();
                final String itemsetId = (String) currentEntry.getKey();
                final List items = (List) currentEntry.getValue();

                ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "itemset", new String[]{"id", itemsetId});
                for (Iterator j = items.iterator(); j.hasNext();) {
                    final XFormsSelect1Control.ItemsetInfo itemsetInfo = (XFormsSelect1Control.ItemsetInfo) j.next();

                    ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "item",
                            new String[]{"label", itemsetInfo.getLabel(), "value", itemsetInfo.getValue()});
                    ch.endElement();
                }
                ch.endElement();
            }
            ch.endElement();
        }
    }

    public static void outputSwitchDivs(Element divsElement, XFormsControls xformsControls) {
        final Map switchIdToSelectedCaseIdMap = xformsControls.getCurrentSwitchState().getSwitchIdToSelectedCaseIdMap();
        if (switchIdToSelectedCaseIdMap != null) {
            // There are some xforms:switch/xforms:case controls

            for (Iterator i = switchIdToSelectedCaseIdMap.entrySet().iterator(); i.hasNext();) {
                final Map.Entry currentEntry = (Map.Entry) i.next();
                final String switchId = (String) currentEntry.getKey();
                final String selectedCaseId = (String) currentEntry.getValue();

                // Output selected ids
                {
                    final Element divElement = divsElement.addElement("xxf:div", XFormsConstants.XXFORMS_NAMESPACE_URI);
                    divElement.addAttribute("id", selectedCaseId);
                    divElement.addAttribute("visibility", "visible");
                }

                // Output deselected ids
                final XFormsControl switchXFormsControl = (XFormsControl) xformsControls.getObjectById(switchId);
                final List children = switchXFormsControl.getChildren();
                if (children != null && children.size() > 0) {
                    for (Iterator j = children.iterator(); j.hasNext();) {
                        final XFormsControl caseXFormsControl = (XFormsControl) j.next();

                        if (!caseXFormsControl.getEffectiveId().equals(selectedCaseId)) {
                            final Element divElement = divsElement.addElement("xxf:div", XFormsConstants.XXFORMS_NAMESPACE_URI);
                            divElement.addAttribute("id", caseXFormsControl.getEffectiveId());
                            divElement.addAttribute("visibility", "hidden");
                        }
                    }
                }
            }
        }
    }

    public static void diffSwitchDivs(ContentHandlerHelper ch, XFormsControls xformsControls, XFormsControls.SwitchState state1, XFormsControls.SwitchState state2) {

        final Map switchIdToSelectedCaseIdMap2 = state2.getSwitchIdToSelectedCaseIdMap();
        if (switchIdToSelectedCaseIdMap2 != null) {
            // There are some xforms:switch/xforms:case controls

            // Obtain previous state
            final Map switchIdToSelectedCaseIdMap1 = state1.getSwitchIdToSelectedCaseIdMap();
            boolean found = false;

            // Iterate over all the switches
            for (Iterator i = switchIdToSelectedCaseIdMap2.entrySet().iterator(); i.hasNext();) {
                final Map.Entry currentEntry = (Map.Entry) i.next();
                final String switchId = (String) currentEntry.getKey();
                final String selectedCaseId = (String) currentEntry.getValue();

                // Only output the information if it has changed
                final String previousSelectedCaseId = (String) switchIdToSelectedCaseIdMap1.get(switchId);
                if (!selectedCaseId.equals(previousSelectedCaseId)) {

                    if (!found) {
                        // Open xxf:divs element
                        ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "divs");
                        found = true;
                    }

                    // Output selected case id
                    ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "div", new String[]{"id", selectedCaseId, "visibility", "visible"});

                    if (previousSelectedCaseId != null) {
                        // Output deselected case ids
                        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "div", new String[]{"id", previousSelectedCaseId, "visibility", "hidden"});
                    } else {
                        // This is a new switch (can happen with repeat), send all deselected to be sure
                        final XFormsControl switchXFormsControl = (XFormsControl) xformsControls.getObjectById(switchId);
                        final List children = switchXFormsControl.getChildren();
                        if (children != null && children.size() > 0) {
                            for (Iterator j = children.iterator(); j.hasNext();) {
                                final XFormsControl caseXFormsControl = (XFormsControl) j.next();

                                if (!caseXFormsControl.getEffectiveId().equals(selectedCaseId)) {
                                    ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "div", new String[]{"id", caseXFormsControl.getEffectiveId(), "visibility", "hidden"});
                                }
                            }
                        }
                    }
                }
            }
            // Close xxf:divs element
            if (found)
                ch.endElement();
        }
    }

    public static XFormsContainingDocument createXFormsContainingDocument(PipelineContext pipelineContext, XFormsState xformsState, Element filesElement) {
        return createXFormsContainingDocument(pipelineContext, xformsState, filesElement, null, null);
    }

    /**
     * Create a ContainingDocument.
     *
     * @param pipelineContext           current pipeline context
     * @param xformsState               XForms state containing static and dynamic state. Static state is ignored if xformsEngineStaticState is provided.
     * @param filesElement              file information used in case of submission
     * @param xformsEngineStaticState   XForms static state information
     * @param initializationURIResolver URIResolver for loading instances during initialization (and possibly more, such as schemas and "GET" submissions upon initialization)
     * @return                          created XFormsContainingDocument
     */
    public static XFormsContainingDocument createXFormsContainingDocument(PipelineContext pipelineContext, XFormsState xformsState,
                                                                          Element filesElement, XFormsEngineStaticState xformsEngineStaticState,
                                                                          XFormsURIResolver initializationURIResolver) {

        if (xformsEngineStaticState == null) {
            // TODO: Handle caching of this.
            xformsEngineStaticState = new XFormsEngineStaticState(pipelineContext, XFormsUtils.decodeXML(pipelineContext, xformsState.getStaticState()));
            logger.debug("XForms - creating new ContainingDocument (static state object not provided).");
        } else {
            logger.debug("XForms - creating new ContainingDocument (static state object provided).");
        }

        final Document dynamicStateDocument;
        {
            final String dynamicStateString = xformsState.getDynamicState();
            dynamicStateDocument = (dynamicStateString == null || "".equals(dynamicStateString)) ? null : XFormsUtils.decodeXML(pipelineContext, dynamicStateString);
        }

        // Get instances from dynamic state
        final Element instancesElement = (dynamicStateDocument == null) ? null : dynamicStateDocument.getRootElement().element("instances");

        // Get divs from dynamic state
        final Element divsElement = (dynamicStateDocument == null) ? null : dynamicStateDocument.getRootElement().element("divs");

        // Get repeat indexes from dynamic state
        final Element repeatIndexesElement = (dynamicStateDocument == null) ? null : dynamicStateDocument.getRootElement().element("repeat-indexes");

        // Get automatic event from dynamic state
        final Element eventElement = (dynamicStateDocument == null) ? null : dynamicStateDocument.getRootElement().element("event");

        // Create XForms Engine ContainingDocument
        final XFormsContainingDocument containingDocument = new XFormsContainingDocument(xformsEngineStaticState, initializationURIResolver, repeatIndexesElement);

        // Get instances
        boolean isInitializeEvent;
        {
            int foundInstancesCount = 0;
            int expectedInstancesCount = 0;
            if (instancesElement != null) {

                // Iterator over all the models
                Iterator modelIterator = containingDocument.getModels().iterator();

                XFormsModel currentModel = null;
                int currentModelInstancesCount = 0;
                int currentCount = 0;

                for (Iterator i = instancesElement.elements().iterator(); i.hasNext();) {
                    Element instanceElement = (Element) i.next();

                    // Go to next model if needed
                    if (currentCount == currentModelInstancesCount) {
                        currentModel = (XFormsModel) modelIterator.next();
                        currentModelInstancesCount = currentModel.getInstanceCount();
                        currentCount = 0;

                        expectedInstancesCount += currentModelInstancesCount;
                    }

                    // Create and set instance document on current model
                    Document instanceDocument = Dom4jUtils.createDocumentCopyParentNamespaces(instanceElement);
                    currentModel.setInstanceDocument(pipelineContext, currentCount, instanceDocument, null, null, null);// TODO: this also resets the URI information for the instance. We should probably keep it and store it in the dynamic state.

                    currentCount++;
                    foundInstancesCount++;
                }
                // Number of instances must be zero or match number of models
                if (foundInstancesCount != 0 && expectedInstancesCount != foundInstancesCount)
                    throw new OXFException("Number of instances (" + foundInstancesCount + ") doesn't match number of instances in models (" + expectedInstancesCount + ").");
            }
            // Initialization will take place if no instances are provided
            isInitializeEvent = foundInstancesCount == 0;
        }

        // Initialize XForms Engine
        if (isInitializeEvent)
            containingDocument.dispatchExternalEvent(pipelineContext, new XXFormsInitializeEvent(containingDocument));
        else
            containingDocument.dispatchExternalEvent(pipelineContext, new XXFormsInitializeStateEvent(containingDocument, divsElement, repeatIndexesElement));

        // Run automatic event if present
        if (eventElement != null) {
            final String controlId = eventElement.attributeValue("source-control-id");
            final String resource = eventElement.attributeValue("resource");
            final String eventName = eventElement.attributeValue("name");

            containingDocument.executeExternalEvent(pipelineContext, eventName, controlId, null, resource, filesElement);
        }

        return containingDocument;
    }

    public static class XFormsState {
        private String staticState;
        private String dynamicState;

        public XFormsState(String staticState, String dynamicState) {
            this.staticState = staticState;
            this.dynamicState = dynamicState;
        }

        public String getStaticState() {
            return staticState;
        }

        public String getDynamicState() {
            return dynamicState;
        }

        public String toString() {
            return staticState + "|" + dynamicState;
        }
    }
}

package ca.uhn.fhir.jpa.term;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.IFhirResourceDaoCodeSystem;
import ca.uhn.fhir.jpa.entity.TermConcept;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.util.CoverageIgnore;
import ca.uhn.fhir.util.UrlUtil;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.hapi.ctx.IValidationSupport;
import org.hl7.fhir.r5.model.*;
import org.hl7.fhir.r5.model.CodeSystem.ConceptDefinitionComponent;
import org.hl7.fhir.r5.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.r5.terminologies.ValueSetExpander;
import org.hl7.fhir.utilities.validation.ValidationMessage.IssueSeverity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2019 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

public class HapiTerminologySvcR5 extends BaseHapiTerminologySvcImpl implements IValidationSupport, IHapiTerminologySvcR5 {

	@Autowired
	@Qualifier("myValueSetDaoR5")
	private IFhirResourceDao<ValueSet> myValueSetResourceDao;
	@Autowired
	@Qualifier("myConceptMapDaoR5")
	private IFhirResourceDao<ConceptMap> myConceptMapResourceDao;
	@Autowired
	private IFhirResourceDaoCodeSystem<CodeSystem, Coding, CodeableConcept> myCodeSystemResourceDao;
	@Autowired
	private IValidationSupport myValidationSupport;
	@Autowired
	private IHapiTerminologySvc myTerminologySvc;
	@Autowired
	private PlatformTransactionManager myTransactionManager;

	/**
	 * Constructor
	 */
	public HapiTerminologySvcR5() {
		super();
	}

	private void addAllChildren(String theSystemString, ConceptDefinitionComponent theCode, List<VersionIndependentConcept> theListToPopulate) {
		if (isNotBlank(theCode.getCode())) {
			theListToPopulate.add(new VersionIndependentConcept(theSystemString, theCode.getCode()));
		}
		for (ConceptDefinitionComponent nextChild : theCode.getConcept()) {
			addAllChildren(theSystemString, nextChild, theListToPopulate);
		}
	}

	private boolean addTreeIfItContainsCode(String theSystemString, ConceptDefinitionComponent theNext, String theCode, List<VersionIndependentConcept> theListToPopulate) {
		boolean foundCodeInChild = false;
		for (ConceptDefinitionComponent nextChild : theNext.getConcept()) {
			foundCodeInChild |= addTreeIfItContainsCode(theSystemString, nextChild, theCode, theListToPopulate);
		}

		if (theCode.equals(theNext.getCode()) || foundCodeInChild) {
			theListToPopulate.add(new VersionIndependentConcept(theSystemString, theNext.getCode()));
			return true;
		}

		return false;
	}

	@Override
	protected IIdType createOrUpdateCodeSystem(org.hl7.fhir.r4.model.CodeSystem theCodeSystemResource) {
		CodeSystem resourceToStore;
		try {
			resourceToStore = org.hl7.fhir.convertors.conv40_50.CodeSystem.convertCodeSystem(theCodeSystemResource);
		} catch (FHIRException e) {
			throw new InternalErrorException(e);
		}
		validateCodeSystemForStorage(theCodeSystemResource);
		if (isBlank(resourceToStore.getIdElement().getIdPart())) {
			String matchUrl = "CodeSystem?url=" + UrlUtil.escapeUrlParam(theCodeSystemResource.getUrl());
			return myCodeSystemResourceDao.update(resourceToStore, matchUrl).getId();
		} else {
			return myCodeSystemResourceDao.update(resourceToStore).getId();
		}
	}

	@Override
	protected void createOrUpdateConceptMap(org.hl7.fhir.r4.model.ConceptMap theConceptMap) {
		ConceptMap resourceToStore;
		try {
			resourceToStore = org.hl7.fhir.convertors.conv40_50.ConceptMap.convertConceptMap(theConceptMap);
		} catch (FHIRException e) {
			throw new InternalErrorException(e);
		}
		if (isBlank(resourceToStore.getIdElement().getIdPart())) {
			String matchUrl = "ConceptMap?url=" + UrlUtil.escapeUrlParam(theConceptMap.getUrl());
			myConceptMapResourceDao.update(resourceToStore, matchUrl);
		} else {
			myConceptMapResourceDao.update(resourceToStore);
		}
	}

	@Override
	protected void createOrUpdateValueSet(org.hl7.fhir.r4.model.ValueSet theValueSet) {
		ValueSet valueSetDstu3;
		try {
			valueSetDstu3 = org.hl7.fhir.convertors.conv40_50.ValueSet.convertValueSet(theValueSet);
		} catch (FHIRException e) {
			throw new InternalErrorException(e);
		}

		if (isBlank(valueSetDstu3.getIdElement().getIdPart())) {
			String matchUrl = "ValueSet?url=" + UrlUtil.escapeUrlParam(theValueSet.getUrl());
			myValueSetResourceDao.update(valueSetDstu3, matchUrl);
		} else {
			myValueSetResourceDao.update(valueSetDstu3);
		}
	}

	@Override
	public ValueSetExpander.ValueSetExpansionOutcome expandValueSet(FhirContext theContext, ConceptSetComponent theInclude) {
		ValueSet valueSetToExpand = new ValueSet();
		valueSetToExpand.getCompose().addInclude(theInclude);

		try {
			org.hl7.fhir.r4.model.ValueSet valueSetToExpandR4;
			valueSetToExpandR4 = org.hl7.fhir.convertors.conv40_50.ValueSet.convertValueSet(valueSetToExpand);
			org.hl7.fhir.r4.model.ValueSet expandedValueSet = super.expandValueSet(valueSetToExpandR4);
			return new ValueSetExpander.ValueSetExpansionOutcome(org.hl7.fhir.convertors.conv40_50.ValueSet.convertValueSet(expandedValueSet));
		} catch (FHIRException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public IBaseResource expandValueSet(IBaseResource theInput) {
		ValueSet valueSetToExpand = (ValueSet) theInput;

		try {
			org.hl7.fhir.r4.model.ValueSet valueSetToExpandR4;
			valueSetToExpandR4 = org.hl7.fhir.convertors.conv40_50.ValueSet.convertValueSet(valueSetToExpand);
			org.hl7.fhir.r4.model.ValueSet expandedR4 = super.expandValueSet(valueSetToExpandR4);
			return org.hl7.fhir.convertors.conv40_50.ValueSet.convertValueSet(expandedR4);
		} catch (FHIRException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void expandValueSet(IBaseResource theValueSetToExpand, IValueSetCodeAccumulator theValueSetCodeAccumulator) {
		ValueSet valueSetToExpand = (ValueSet) theValueSetToExpand;

		try {
			org.hl7.fhir.r4.model.ValueSet valueSetToExpandR4;
			valueSetToExpandR4 = org.hl7.fhir.convertors.conv40_50.ValueSet.convertValueSet(valueSetToExpand);
			super.expandValueSet(valueSetToExpandR4, theValueSetCodeAccumulator);
		} catch (FHIRException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<VersionIndependentConcept> expandValueSet(String theValueSet) {
		ValueSet vs = myValidationSupport.fetchResource(myContext, ValueSet.class, theValueSet);
		if (vs == null) {
			return Collections.emptyList();
		}

		org.hl7.fhir.r4.model.ValueSet valueSetToExpandR4;
		try {
			valueSetToExpandR4 = org.hl7.fhir.convertors.conv40_50.ValueSet.convertValueSet(vs);
		} catch (FHIRException e) {
			throw new InternalErrorException(e);
		}


		return expandValueSetAndReturnVersionIndependentConcepts(valueSetToExpandR4);
	}

	@Override
	public List<IBaseResource> fetchAllConformanceResources(FhirContext theContext) {
		return null;
	}

	@Override
	public List<StructureDefinition> fetchAllStructureDefinitions(FhirContext theContext) {
		return Collections.emptyList();
	}

	@CoverageIgnore
	@Override
	public CodeSystem fetchCodeSystem(FhirContext theContext, String theSystem) {
		return null;
	}

	@Override
	public IBaseResource fetchResource(FhirContext theContext, Class theClass, String theUri) {
		return null;
	}

	@CoverageIgnore
	@Override
	public ValueSet fetchValueSet(FhirContext theContext, String theSystem) {
		return null;
	}

	@CoverageIgnore
	@Override
	public StructureDefinition fetchStructureDefinition(FhirContext theCtx, String theUrl) {
		return null;
	}

	private void findCodesAbove(CodeSystem theSystem, String theSystemString, String theCode, List<VersionIndependentConcept> theListToPopulate) {
		List<ConceptDefinitionComponent> conceptList = theSystem.getConcept();
		for (ConceptDefinitionComponent next : conceptList) {
			addTreeIfItContainsCode(theSystemString, next, theCode, theListToPopulate);
		}
	}

	@Override
	public List<VersionIndependentConcept> findCodesAboveUsingBuiltInSystems(String theSystem, String theCode) {
		ArrayList<VersionIndependentConcept> retVal = new ArrayList<>();
		CodeSystem system = myValidationSupport.fetchCodeSystem(myContext, theSystem);
		if (system != null) {
			findCodesAbove(system, theSystem, theCode, retVal);
		}
		return retVal;
	}

	private void findCodesBelow(CodeSystem theSystem, String theSystemString, String theCode, List<VersionIndependentConcept> theListToPopulate) {
		List<ConceptDefinitionComponent> conceptList = theSystem.getConcept();
		findCodesBelow(theSystemString, theCode, theListToPopulate, conceptList);
	}

	private void findCodesBelow(String theSystemString, String theCode, List<VersionIndependentConcept> theListToPopulate, List<ConceptDefinitionComponent> conceptList) {
		for (ConceptDefinitionComponent next : conceptList) {
			if (theCode.equals(next.getCode())) {
				addAllChildren(theSystemString, next, theListToPopulate);
			} else {
				findCodesBelow(theSystemString, theCode, theListToPopulate, next.getConcept());
			}
		}
	}

	@Override
	public List<VersionIndependentConcept> findCodesBelowUsingBuiltInSystems(String theSystem, String theCode) {
		ArrayList<VersionIndependentConcept> retVal = new ArrayList<>();
		CodeSystem system = myValidationSupport.fetchCodeSystem(myContext, theSystem);
		if (system != null) {
			findCodesBelow(system, theSystem, theCode, retVal);
		}
		return retVal;
	}

	@Override
	protected org.hl7.fhir.r4.model.CodeSystem getCodeSystemFromContext(String theSystem) {
		CodeSystem codeSystem = myValidationSupport.fetchCodeSystem(myContext, theSystem);
		try {
			return org.hl7.fhir.convertors.conv40_50.CodeSystem.convertCodeSystem(codeSystem);
		} catch (FHIRException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public boolean isCodeSystemSupported(FhirContext theContext, String theSystem) {
		return myTerminologySvc.supportsSystem(theSystem);
	}

	@Override
	public StructureDefinition generateSnapshot(StructureDefinition theInput, String theUrl, String theWebUrl, String theProfileName) {
		return null;
	}

	@CoverageIgnore
	@Override
	public CodeValidationResult validateCode(FhirContext theContext, String theCodeSystem, String theCode, String theDisplay) {
		TransactionTemplate txTemplate = new TransactionTemplate(myTransactionManager);
		txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		return txTemplate.execute(t->{
			Optional<TermConcept> codeOpt = myTerminologySvc.findCode(theCodeSystem, theCode);
			if (codeOpt.isPresent()) {
				ConceptDefinitionComponent def = new ConceptDefinitionComponent();
				TermConcept code = codeOpt.get();
				def.setCode(code.getCode());
				def.setDisplay(code.getDisplay());
				CodeValidationResult retVal = new CodeValidationResult(def);
				retVal.setProperties(code.toValidationProperties());
				retVal.setCodeSystemName(code.getCodeSystemVersion().getCodeSystem().getName());
				return retVal;
			}

			return new CodeValidationResult(IssueSeverity.ERROR, "Unknown code {" + theCodeSystem + "}" + theCode);
		});

	}

	@Override
	public LookupCodeResult lookupCode(FhirContext theContext, String theSystem, String theCode) {
		return super.lookupCode(theContext, theSystem, theCode);
	}


}
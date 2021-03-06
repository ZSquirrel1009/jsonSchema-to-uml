package edu.uoc.som.jsonschematouml.generators;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map.Entry;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.uml2.uml.AggregationKind;
import org.eclipse.uml2.uml.Association;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Comment;
import org.eclipse.uml2.uml.Constraint;
import org.eclipse.uml2.uml.Element;
import org.eclipse.uml2.uml.Enumeration;
import org.eclipse.uml2.uml.Model;
import org.eclipse.uml2.uml.OpaqueExpression;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.PrimitiveType;
import org.eclipse.uml2.uml.Property;
import org.eclipse.uml2.uml.Type;
import org.eclipse.uml2.uml.UMLFactory;
import org.eclipse.uml2.uml.UMLPackage;
import org.eclipse.uml2.uml.resource.UMLResource;

import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;

import edu.uoc.som.jsonschematouml.validator.JSONSchemaValidator;

/**
 * Entry point for the JSONSchemaToUML tool. You should use this class as a fa�ade for everything provided by the tool.
 *
 * Be aware that the current implementation is just a prototype to validate the feasibility of the idea. Further
 * versions should refine this code and apply good Java practices. For now, this is just a proof-of-concept.
 *
 * The inner workings of this class is pretty straight-forward. Given a {@link File} (which can be a folder or a JSON
 * file), the tool analyzes the document/s according to the JSON schema validation specification and creates the
 * corresponding UML model.
 */
public class JSONSchemaToUML {
	/**
	 * The default name to give if no one is provided
	 */
	public static String DEFAULT_MODEL_NAME = "test";

	/**
	 * This class is used to represent proxy associations (i.e., the type will be resolved later)
	 *
	 */
	class ProxyAssociation {
		/**
		 * The class tha owns this association
		 */
		Class owner;
		/**
		 * If the association is a composite one (at source/target)
		 */
		boolean sourceComposition, targetComposition;
		/**
		 * The aggregation kind of the association ends (source/target)
		 */
		AggregationKind sourceKind, targetKind;
		/**
		 * The name of the association ends
		 */
		String sourceEnd, targetEnd;
		/**
		 * Cardinalities
		 */
		int sourceUpper, sourceLower, targetUpper, targetLower;
	}

	/**
	 * The Oracle is used to keep track of every concept (i.e., Class) created.
	 * URIs are used to index the elements
	 */
	HashMap<String, Class> oracle = new HashMap<>();

	/**
	 * The references to classes used as superclasses found during the analysis
	 * (to be later resolved by {@link #resolveSuperclasses()}
	 */
	HashMap<JSONSchemaURI, Class> superclassesFound = new HashMap<>();

	/**
	 * The references to classes used in associations found during the analysis
	 * (to be later resolved by {@link #resolveAssociations()}
	 */
	HashMap<JSONSchemaURI, ProxyAssociation> associationsFound = new HashMap<>();

	/**
	 * As we will generate UML models, we use the Eclipse UML2 Factory
	 */
	private UMLFactory umlFactory;

	/**
	 * The resource set where the model will be stored. We keep it beacuse we have to
	 * configure and customize some options
	 */
	private ResourceSet resourceSet;

	/**
	 * The model being created, the target.
	 */
	private Model model;

	/**
	 * We keep the current package being created
	 * TODO Try not to use an instance variable
	 */
	private Package umlPackage;
	
	/**
	 * We keep the root package (to add the primitive types)
	 */
	private Package rootPackage;

	/**
	 * We use this class as proxy when we cannot locate a referenced class
	 */
	private Class unknown;

	/**
	 * Primitive types to be used in the model
	 */
	private HashMap<String, PrimitiveType> primitiveTypes = new HashMap<>();

	/**
	 * Delegated constructor, it calls the {@link JSONSchemaToUML} constructor and uses the
	 * value of {@link JSONSchemaToUML.DEFAULT_MODEL_NAME} as model name
	 */
	public JSONSchemaToUML() {
		initModel(DEFAULT_MODEL_NAME);
	}

	/**
	 * Main constructor of the class. It basically initializes the model and the oracle
	 * 
	 * @param modelName The name for the model (and also the resulting file)
	 */
	public JSONSchemaToUML(String modelName) {
		initModel(modelName);
	}

	/**
	 * Returns the model being discovered
	 * @return The model
	 */
	public Model getModel() {
		return model;
	}

	/**
	 * Launches the tool to traverse a file/folder with JSON schemas and generate the corresponding UML models
	 * @param inputFile The file to analyze (it can be a file or a folder, if folder, it will be recursively traversed)
	 */
	public void launch(File inputFile) {
		if(inputFile == null || !inputFile.exists())
			throw new JSONSchemaToUMLException("The file must exist");
		analyze(inputFile);
		resolveAssociations();
		resolveSuperclasses();
	}

	/**
	 * Initializates the target model (i.e., gives a name, creates some annotations) and configures the resource set
	 * to use the propor UML primitive types
	 * @param modelName The name of the model
	 */
	private void initModel(String modelName) {
		// Creating the model
		umlFactory = UMLFactory.eINSTANCE;
		model = umlFactory.createModel();
		model.setName(modelName);

		// Configurating the resource set
		resourceSet = new ResourceSetImpl();
		resourceSet.getPackageRegistry().put(UMLPackage.eNS_URI, UMLPackage.eINSTANCE);
		resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put(UMLResource.FILE_EXTENSION, UMLResource.Factory.INSTANCE);

		// Main package
		umlPackage = model.createNestedPackage(modelName);
		rootPackage = umlPackage;

		// Class to reuse when something goes wrong
		unknown = umlPackage.createOwnedClass("Unknown", false);

	}

	/**
	 * Analyzes a fodler/file. If it is a folder, it recursively navigates to find the files.
	 * Each inner folder becomes a UML package.
	 * 
	 * @param inputFile A Folder or a file to analyze.
	 */
	private void analyze(File inputFile) {
		if(inputFile.isFile()) {
			// If the file is NOT a valid JSON Schema, we skip it
			try {
				if(!JSONSchemaValidator.validate(inputFile).isSuccess()) {
					System.err.println("The file " + inputFile.getAbsolutePath() + " is not a valid JSON Schema");
					return;
				}
			} catch (IOException | ProcessingException e) {
				System.err.println("The file " + inputFile.getAbsolutePath() + " is not a valid JSON file");
				return;
			}
			analyzeSchema(inputFile);
		} else if(inputFile.isDirectory()) {
			Package oldPackage = umlPackage;
			umlPackage = oldPackage.createNestedPackage(inputFile.getName());
			for(File inFile: inputFile.listFiles()) 
				analyze(inFile);
			umlPackage = oldPackage;
		} else
			throw new JSONSchemaToUMLException("Invalid input");
	}

	/**
	 * Analyzes a file conforming to the JSON schema in order to create the corresponding UML elements (which will
	 * be stored in both the oracle and the model)
	 * 
	 * @param file The file to analyze
	 */
	private void analyzeSchema(File file) {
		// Let's start with the root element of the file
		JsonObject rootElement = null;
		try {
			JsonElement inputElement = (new JsonParser()).parse(new JsonReader(new FileReader(file)));
			rootElement = inputElement.getAsJsonObject();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		String modelConceptName = file.getName().substring(0, file.getName().indexOf("."));
		// Basic info from the schema
		if(rootElement.has("id")) { 
			String id = rootElement.get("id").getAsString();
			JSONSchemaURI jsu = new JSONSchemaURI(id);
			modelConceptName = jsu.digestIdName();
		}
		analyzeRootSchemaElement(modelConceptName, rootElement);
	}

	/**
	 * Basic analyzer for JSON schema elements for which we already know that they are objects (or definitions)
	 * and therefore will become concepts
	 * @param name The name of the element
	 * @param rootElement The JSON root element
	 */
	private void analyzeRootSchemaElement(String name, JsonObject rootElement) {
		if(rootElement.has("type") || rootElement.has("allOf")) {
			analyzeObject(name, rootElement);
		} 
		
		if(rootElement.has("definitions")) {
			// Section 9 in json-validation
			analyzeDefinitions(rootElement);
		} 
	}

	/**
	 * Analyzer for objects in the JSON schema. Objects are normally mapped into a corresponding UML class.
	 * 
	 * @param modelConceptName The name of the element
	 * @param object The JSON object element
	 */
	private Class analyzeObject(String modelConceptName, JsonObject object) {
		// Creating the concept
		String camelCasedModelConceptName = modelConceptName.substring(0, 1).toUpperCase() + modelConceptName.substring(1);
		Class concept = umlPackage.createOwnedClass(camelCasedModelConceptName, false);

		if(object.has("title")) {
			// 10.1 section in json-validation
			String title = object.get("title").getAsString();
			Comment comment = UMLFactory.eINSTANCE.createComment();
			comment.setBody("Title: " + title);
			concept.getOwnedComments().add(comment);
		}

		if(object.has("description")) {
			// 10.1 section in json-validation
			String title = object.get("description").getAsString();
			Comment comment = UMLFactory.eINSTANCE.createComment();
			comment.setBody("Description: " + title);
			concept.getOwnedComments().add(comment);
		}

		// Storing the concept
		oracle.put(modelConceptName, concept);

		if(object.has("allOf")) {
			// allOf represents a concept which has to successfully validate against all the schema elements
			// defined inside. We create an element which includes all the information described by allOf
			JsonArray allOfArray = object.get("allOf").getAsJsonArray();
			for(JsonElement allOfElement : allOfArray) {
				JsonObject allOfElementObj = allOfElement.getAsJsonObject();
				if(allOfElementObj.has("$ref")) {
					// We interpret $ref elements as super classes for this element
					// As such, the element should have been analyzed previously
					String ref = allOfElementObj.get("$ref").getAsString();
					JSONSchemaURI jsu = new JSONSchemaURI(ref);
					// We mark the concept to have a super class, it will be resolved
					// afterwards by the {@link #resolveSuperclasses()} method
					superclassesFound.put(jsu, concept);
				} else if(allOfElementObj.has("properties")) {
					// Properties elements will become the attributes/references of the element
					JsonObject propertiesObj = allOfElementObj.get("properties").getAsJsonObject();
					for (Entry<String, JsonElement> entry : propertiesObj.entrySet()) {
						String propertyKey = entry.getKey();
						JsonObject propertyObj = propertiesObj.get(propertyKey).getAsJsonObject();
						analyzeProperty(concept, propertyKey, propertyObj);
					}
				} 
			}
		} else if (object.has("oneOf")) { 
			analyzeOneOf(concept, concept.getName(), concept.getName() + "Option", object, false);
		} else if (object.has("properties")) {
			// When an element has directly "properties" may mean that it does not have superclasses
			// It is also used in definitions
			JsonObject propertiesObj = object.get("properties").getAsJsonObject(); 
			for (Entry<String, JsonElement> entry : propertiesObj.entrySet()) {
				String propertyKey = entry.getKey();
				JsonObject propertyObj = propertiesObj.get(propertyKey).getAsJsonObject();
				analyzeProperty(concept, propertyKey, propertyObj);
			}
		} else if (object.has("type") && !object.get("type").getAsString().equals("object")) {
			// Special case: the element is not really an object
			// We will create a fake class with an attribute including the information schema of the
			// JSON object
			analyzeProperty(concept, concept.getName()+ "Attribute", object);
		}
		
		if (object.has("required")) {
			// 6.5.3 section in json-validation
			// This constraint specifies the set of properties that have to be there (e.g., the min
			// cardinality is 1. Only properties that are not coming from arrays are touched (i.e., 
			// those properties with upper limit <= 1)
			for(JsonElement reqElem : object.get("required").getAsJsonArray()) {
				String reqElemString = reqElem.getAsString();
				for(Property property : concept.getOwnedAttributes()) {
					if(property.getName().equals(reqElemString) && property.getUpper() < 2) {
						property.setLower(1);
						break;
					}
				}
			}
		}

		return concept;
	}

	/**
	 * Analyzes a property for an object/concept
	 * @param concept The concept which includes such property
	 * @param propertyName The name of the property
	 * @param object The JSON object element to analyze
	 */
	private void analyzeProperty(Class concept, String propertyName, JsonObject object) {
		Element createdElement = null;
		boolean nullable = false;

		if(object.has("type")) {
			// We recover the type JSON element
			// According to section 6.1.1 in json-schema-validation, type can be either a string
			// or an array. If it is array, we only consider the first element, and take into 
			// consideration the second value if it is a "null" value to set cardinality.
			String propertyObjType = null;
			if (object.get("type") instanceof JsonPrimitive) {
				propertyObjType= object.get("type").getAsString();
			} else if(object.get("type") instanceof JsonArray) {
				JsonArray typeArray = (JsonArray) object.get("type").getAsJsonArray();
				propertyObjType = typeArray.get(0).getAsString();
				if(typeArray.size() > 1) {
					if(typeArray.get(1).getAsString().equals("null"))
						nullable = true; // TODO Consider in the metamodel. how exactly?
				} 
			}
			
			// We analyze the type
			Type modelAttType = null;
			if(object.has("enum")) {
				// Section 6.1.2. We create an enumeration
				createdElement = analyzeEnumProperty(concept, propertyName, object);
			} else if (propertyObjType.equals("string")) {
				if(object.has("format")) {
					String propertyFormat = object.get("format").getAsString();
					if(propertyFormat.equals("date-time")) {
						modelAttType = getPrimitiveType("Date");
					}
				}
				if(modelAttType == null)
					modelAttType = getPrimitiveType("String");

				if(object.has("maxLength"))
					// Section 6.3.1 in json-schema-validation. Resolved as OCL
					addConstraint(concept, propertyName, "maxLengthConstraint",
							"self." + propertyName + ".size() <= " + object.get("maxLength").getAsString());
				if(object.has("minLength"))
					// Section 6.3.2 in json-schema-validation. Resolved as OCL
					addConstraint(concept, propertyName, "minLengthConstraint",
							"self." + propertyName + ".size() >= " + object.get("minLength").getAsString());
				if(object.has("pattern")) {
					// Section 6.3.3 in json-schema-validation. Resolved as OCL, possible?
					// TODO 6.3.3 in json-schema-validation
				}
				createdElement = concept.createOwnedAttribute(propertyName, modelAttType);
			} else if(propertyObjType.equals("integer") || propertyObjType.equals("number")) {
				modelAttType = getPrimitiveType("Integer");
				createdElement = concept.createOwnedAttribute(propertyName, modelAttType);
				if(object.has("multipleOf"))
					// Section 6.2.1 in json-schema-validation. Resolved as OCL
					addConstraint(concept, propertyName, "multipleOfConstraint",
							"self." + propertyName + ".div("+object.get("multipleOf").getAsString()+") = 0");
				if(object.has("maximum"))
					// Section 6.2.2 in json-schema-validation. Resolved as OCL
					addConstraint(concept, propertyName, "maximumConstraint",
							"self." + propertyName + " <= " + object.get("maximum").getAsString());
				if(object.has("exclusiveMaximum"))
					// Section 6.2.3 in json-schema-validation. Resolved as OCL
					addConstraint(concept, propertyName, "exclusiveMaximumConstraint",
							"self." + propertyName + " < " + object.get("exclusiveMaximum").getAsString());
				if(object.has("minimum"))
					// Section 6.2.4 in json-schema-validation. Resolved as OCL
					addConstraint(concept, propertyName, "minimumConstraint",
							"self." + propertyName + " >= " + object.get("minimum").getAsString());
				if(object.has("exclusiveMinimum"))
					// Section 6.2.5 in json-schema-validation. Resolved as OCL
					addConstraint(concept, propertyName, "exclusiveMinimumConstraint",
							"self." + propertyName + " > " + object.get("exclusiveMinimum").getAsString());

			} else if(propertyObjType.equals("boolean")) {
				createdElement = concept.createOwnedAttribute(propertyName, getPrimitiveType("Boolean"));
			} else if(propertyObjType.equals("array")) {
				// Section 6.4.1 in json-schema-validation. 
				
				// If the items key is an array, we only consider the first one
				// (as in UML we cannot have a multi-valued attribute with multitypes
				// TODO Should we created a hierarchy?
				
				JsonObject itemsObject = null;
				if(object.has("items") && object.get("items").isJsonArray()) {
					itemsObject = object.get("items").getAsJsonArray().get(0).getAsJsonObject();
				} else {
					itemsObject = object.get("items").getAsJsonObject();
				}
				
				if(object.has("items")) {
					if(itemsObject.has("enum")) {
						createdElement = analyzeEnumProperty(concept, propertyName, itemsObject);
						((Property) createdElement).setUpper(-1);
					} else if(itemsObject.has("type") && itemsObject.get("type").getAsString().equals("string")) { 
						createdElement = concept.createOwnedAttribute(propertyName, getPrimitiveType("String"));
						((Property) createdElement).setUpper(-1);
					} else if(itemsObject.has("type") && itemsObject.get("type").getAsString().equals("number")) { 
						createdElement = concept.createOwnedAttribute(propertyName, getPrimitiveType("Integer"));
						((Property) createdElement).setUpper(-1);
					} else if (itemsObject.has("oneOf")) {
						Association oneOfAssociation = analyzeOneOf(concept, propertyName, propertyName + "Option", itemsObject, true);
					} else if (itemsObject.has("anyOf")) {
						Association anyOfAssociation = analyzeAnyOf(concept, propertyName, propertyName + "Option", itemsObject);
					} else if (itemsObject.has("allOf")) {
						// TODO
					} else if (itemsObject.has("properties")) {
						// If an array includes an object with properties key it means that it defines an
						// inner concept so we create a new UML class
						String propertyConceptName = propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1, propertyName.length());
						Class propertyConcept = umlPackage.createOwnedClass(propertyConceptName, false);

						JsonObject propertiesObj = itemsObject.get("properties").getAsJsonObject(); 
						for (Entry<String, JsonElement> entry : propertiesObj.entrySet()) {
							String propertyKey = entry.getKey();
							JsonObject propertyObj = propertiesObj.get(propertyKey).getAsJsonObject();
							analyzeProperty(propertyConcept, propertyKey, propertyObj);
						}
						
						int upper = -1;
						int lower = 0;
						if(object.has("minItems"))
							lower = object.get("minItems").getAsInt();
						if(object.has("maxItems"))
							upper = object.get("maxItems").getAsInt();
						createdElement = concept.createAssociation(true, AggregationKind.NONE_LITERAL, propertyName, lower, upper, propertyConcept, false, AggregationKind.NONE_LITERAL, concept.getName(), 1, 1);
					} else if(itemsObject.has("$ref")) {
						analyzeRef(concept, propertyName, itemsObject);
					}
				} else if(object.has("items") && object.get("items").isJsonArray()) {
					JsonArray itemsObjectArray = object.get("items").getAsJsonArray();
				}
					
				if(createdElement != null && createdElement instanceof Property) {
					if(object.has("maxItems")) {
						// Section 6.4.3 in json-schema-validation. 
						int maxItems = object.get("maxItems").getAsInt();
						((Property) createdElement).setUpper(maxItems);
					}
					if(object.has("minItems")) {
						// Section 6.4.4 in json-schema-validation. 
						int minItems = object.get("minItems").getAsInt();
						((Property) createdElement).setLower(minItems);
					}
				}
			} else if (propertyObjType.equals("object")) {
				String toCamelCase = propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1, propertyName.length());
				Class target = analyzeObject(toCamelCase, object);
				createdElement = concept.createAssociation(true, AggregationKind.NONE_LITERAL, propertyName, 0, 1, target, false, AggregationKind.NONE_LITERAL, concept.getName(), 1, 1);
			} 
		} else if(object.has("$ref")) {
			analyzeRef(concept, propertyName, object);
		} else if(object.has("oneOf")) {
			// Section 6.7.3 in json-schema-validation
			Association oneOfAssociation = analyzeOneOf(concept, propertyName, concept.getName() + "Option", object, true);
		} else if(object.has("anyOf")) {
			// Section 6.7.2 in json-schema-validation
			Association oneOfAssociation = analyzeAnyOf(concept, propertyName, concept.getName() + "Option", object);
		}

		// We check if there is a description and add such info as comment to the created element
		if(createdElement != null && object.has("description")) {
			String description = object.get("description").getAsString();
			Comment comment = UMLFactory.eINSTANCE.createComment();
			comment.setBody("Description: " + description);
			createdElement.getOwnedComments().add(comment);
		}
	}

	/**
	 * Analyzes $ref elements. They are usualy pointers to other elements so an association proxy is created
	 * 
	 * @param concept The concept holding the attribute which is a $ref
	 * @param propertyName The attribute which is a $ref
	 * @param object The object including the $ref information
	 */
	private void analyzeRef(Class concept, String propertyName, JsonObject object) {
		if(!object.has("$ref")) 
			throw new JSONSchemaToUMLException("The object must include an '$ref' key");
		
		String ref = object.get("$ref").getAsString();
		JSONSchemaURI jsu = new JSONSchemaURI(ref);
		String refClassName = jsu.digestFragmentName();
		ProxyAssociation proxy = new ProxyAssociation();
		proxy.sourceComposition = true; proxy.targetComposition = false;
		proxy.sourceKind = AggregationKind.NONE_LITERAL; proxy.targetKind = AggregationKind.NONE_LITERAL;
		proxy.sourceEnd = propertyName; proxy.targetEnd = refClassName;
		proxy.sourceLower = 0; proxy.targetLower = 1;
		proxy.sourceUpper = 1; proxy.targetUpper = 1;
		proxy.owner = concept;
		associationsFound.put(jsu, proxy);
	}

	/**
	 * Factorizes the behavior for dealing with OneOf schema element.
	 * We create a hierarchy for the options and then an associationg pointing at the hierarchy root
	 * 
	 * Section 6.7.3 in json-schema-validation. 
	 * 
	 * @param concept The class that holds the property
	 * @param propertyName The name of the property
	 * @param conceptOptionName The name to give to the option class
	 * @param object The JSON Object
	 * @param mapAsAssociation If true, the options are used as association. Otherwise it will be a hierarchy
	 * @return The association
	 */
	private Association analyzeOneOf(Class concept, String propertyName, String conceptOptionName, JsonObject object, boolean mapAsAssociation) {
		Association createdElement = null;
		
		Class optionClass = null;
		if(mapAsAssociation) {
			String oneOfName = propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1, propertyName.length()) + "Option";
			optionClass = umlPackage.createOwnedClass(oneOfName, false);
			optionClass.setIsAbstract(true);
			createdElement = concept.createAssociation(true, AggregationKind.NONE_LITERAL, propertyName, 1, 1, optionClass, false, AggregationKind.NONE_LITERAL, concept.getName(), 1, 1);
		} else {
			optionClass = concept;
		}

		JsonArray oneOfArray = object.get("oneOf").getAsJsonArray();
		char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUWXYZ".toCharArray();
		int counter = 0;
		for(JsonElement arrayElement : oneOfArray ) {
			if (arrayElement instanceof JsonObject) {
				JsonObject arrayObject = (JsonObject) arrayElement;
				String conceptElementName = conceptOptionName + alphabet[counter++];
				if(arrayObject.has("type") || arrayObject.has("$ref")) {
					// We are dealing with an inline object (no schema header)
					Class conceptElement = umlPackage.createOwnedClass(conceptElementName, false);
					analyzeProperty(conceptElement, "optionAttribute", arrayObject);
					conceptElement.getSuperClasses().add(optionClass);	
				} else if(arrayObject.has("properties" )) {
					// We are deadling with a schema definition (with headers like "title")
					Class conceptElement = analyzeObject(conceptElementName, arrayObject);
					conceptElement.getSuperClasses().add(optionClass);	
				}
			}
		}
		
		return createdElement;
	}
	
	/**
	 * Factorizes the behavior for dealing with AnyOf schema element.
	 * This method is almost a mirror of {@link JSONSchemaToUML.analyzeOneOf}
	 * We create a hierarchy for the options and then an associationg pointing at the hierarchy root
	 * 
	 * Section 6.7.2 in json-schema-validation. 
	 * 
	 * @param concept The class that holds the property
	 * @param propertyName The name of the property
	 * @param optionName The name to give to the option class
	 * @param object The JSON Object
	 * @return The association
	 */
	private Association analyzeAnyOf(Class concept, String propertyName, String optionName, JsonObject object) {
		Association createdElement = null;
		
		String oneOfName = propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1, propertyName.length()) + "Option";
		Class optionClass = umlPackage.createOwnedClass(oneOfName, false);
		optionClass.setIsAbstract(true);
		createdElement = concept.createAssociation(true, AggregationKind.NONE_LITERAL, propertyName, 1, -1, optionClass, false, AggregationKind.NONE_LITERAL, concept.getName(), 1, 1);

		JsonArray oneOfArray = object.get("anyOf").getAsJsonArray();
		char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUWXYZ".toCharArray();
		int counter = 0;
		for(JsonElement arrayElement : oneOfArray ) {
			if (arrayElement instanceof JsonObject) {
				JsonObject arrayObject = (JsonObject) arrayElement;
				String conceptElementName = optionName + alphabet[counter++];
				Class conceptElement = umlPackage.createOwnedClass(conceptElementName, false);
				analyzeProperty(conceptElement, "optionAttribute", arrayObject);
				conceptElement.getSuperClasses().add(optionClass);
			}
		}
		
		return createdElement;
	}
	
	
	/**
	 * Factorizes the behavior for enum types in properties.
	 * 
	 * Section 6.1.2 in json-schema-validation. 
	 * 
	 * @param concept The class that holds the property
	 * @param propertyName The name of the property
	 * @param object The JSON Object
	 * @return The property created
	 */
	private Property analyzeEnumProperty(Class concept, String propertyName, JsonObject object) {
		if(!object.has("enum")) 
			throw new JSONSchemaToUMLException("The object must include an 'enum' key");
			
		JsonArray enumValues = object.get("enum").getAsJsonArray();
		Enumeration enumeration = umlPackage.createOwnedEnumeration(propertyName+"Enum");
		for(JsonElement enumValueElem : enumValues) {
			String enumValue = enumValueElem.getAsString();
			enumeration.getOwnedLiterals().add(enumeration.createOwnedLiteral(enumValue));
		}
		return concept.createOwnedAttribute(propertyName, enumeration); 
	}
	
	/**
	 * Adds a OCL constraint to a concept
	 * @param concept The concept which holds the constraint
	 * @param constraintName The name of the constraint (will be eventually formed
	 *                       as conceptName-constraintName-constraintType
	 * @param constraintType The type of the constraint being applied (e.g., macLengthConstraint)
	 * @param constraintExp The OCL expression
	 */
	private void addConstraint(Class concept, String constraintName, String constraintType, String constraintExp) {
		Constraint constraint = UMLFactory.eINSTANCE.createConstraint();
		String constraintId= concept.getName()+"-"+constraintName+"-"+constraintType;
		constraint.setName(constraintId);
		OpaqueExpression expression = UMLFactory.eINSTANCE.createOpaqueExpression();
		expression.getLanguages().add("OCL");
		expression.getBodies().add(constraintExp);
		constraint.setSpecification(expression);
		concept.getOwnedRules().add(constraint);
	}

	/**
	 * Query the oracle to get a previously created class given a name
	 * @param refClassName The name to look up
	 * @return The found class (null if nothing)
	 */
	private Class queryOracle(String refClassName) {
		if(oracle.containsKey(refClassName)) {
			return oracle.get(refClassName);
		} else
			return null;
	}

	/**
	 * Resolve the associations of the classes. The analysis process includes proxies to be resolved
	 * afterwards. They are resolved by this method :)
	 */
	private void resolveAssociations() {
		for(JSONSchemaURI refClassURI : associationsFound.keySet()) {
			ProxyAssociation proxy = associationsFound.get(refClassURI);
			Class owner = proxy.owner;
			Class foundClass = null;

			if(refClassURI.getFragment() != null) {
				foundClass = queryOracle(refClassURI.digestFragmentName());
			} else {
				foundClass = queryOracle(refClassURI.digestName());
			}
			
			if(foundClass == null) {
				foundClass = unknown;
			}
			owner.createAssociation(proxy.sourceComposition, proxy.sourceKind, proxy.sourceEnd, proxy.sourceLower, proxy.sourceUpper, foundClass, proxy.targetComposition, proxy.targetKind, proxy.targetEnd, proxy.targetLower, proxy.targetUpper);
		}
	}

	/**
	 * Resolve superclasses. The analysis process registers the superclasses to be resolved afterward.
	 * They are resolved by this method :)
	 */
	private void resolveSuperclasses() {
		for(JSONSchemaURI refClassURI : superclassesFound.keySet()) {
			Class subClass = superclassesFound.get(refClassURI);
			Class foundClass = null;
			if(refClassURI.getFragment() != null) {
				foundClass = queryOracle(refClassURI.digestFragmentName());
			} else {
				foundClass = queryOracle(refClassURI.digestName());
			}
			if(foundClass == null) 
				foundClass = unknown;
			subClass.getSuperClasses().add(foundClass);
		}
	}

	/**
	 * Definition are usually created to be reused among the different JSON schemas.
	 * @param object The JSON object including the definitions
	 */
	private void analyzeDefinitions(JsonObject object) {
		JsonObject definitionsObj = object.get("definitions").getAsJsonObject();
		for(Entry<String, JsonElement> entry : definitionsObj.entrySet()) {
			String definitionKey = entry.getKey();
			JsonObject definitionObj = definitionsObj.get(definitionKey).getAsJsonObject();
			analyzeRootSchemaElement(definitionKey, definitionObj);
		}
	}

	/**
	 * Saves the model. It uses the resource set configured previously, as it includes some options to properly
	 * resolve pathmaps and so on.
	 */
	public void saveModel(File target) {
		Resource resource = resourceSet.createResource(URI.createFileURI(target.getAbsolutePath())); // TODO Configure the name
		resource.getContents().add(model);
		try {
			resource.save(null);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Saves the model given an URI
	 * 
	 * @param target the target URI
	 */
	public void saveModel(URI target) {
		Resource resource = resourceSet.createResource(target);
		resource.getContents().add(model);
		try {
			resource.save(null);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Returns (or create) the UML primitive type for a given string-based name.
	 * Primitive types are created on demand.
	 * 
	 * @param commonName The string-based name of the type
	 * @param model The model element
	 * @return The primitive type
	 */
	private PrimitiveType getPrimitiveType(String typeName) {
		PrimitiveType found = primitiveTypes.get(typeName);
		if(found == null) {
			found = umlFactory.createPrimitiveType();
			found.setName(typeName);
			rootPackage.getOwnedTypes().add(found);
			primitiveTypes.put(typeName, found);

		} 
		return found;
	}
}

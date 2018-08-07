/*
@see https://stackoverflow.com/questions/43808524/create-release-notes-using-jira-rest-api-in-html-format-in-groovy
*/
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate;

import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.MutableIssue;

import org.apache.commons.codec.binary.Base64;

import groovy.json.JsonBuilder;
import groovy.transform.BaseScript;
import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import groovy.xml.MarkupBuilder;
import groovy.xml.MarkupBuilderHelper;
import groovyx.net.http.RESTClient;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.MediaType;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.util.regex.*;
import java.util.regex.Pattern;
import javax.swing.text.html.HTMLDocument;
import javax.swing.JEditorPane;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;

import static java.util.stream.Collectors.joining;

@BaseScript CustomEndpointDelegate delegate

getReport(httpMethod: "GET", groups: ["X3 Users"]) { MultivaluedMap queryParams ->
    // Response
  	MediaType mediaType = MediaType.TEXT_PLAIN_TYPE; // or "text/plain"
	ResponseBuilder responseBuilder;
    def bodyResponse, typeResponse;

    // def bodyResponse = new JsonBuilder([abc: 42]).toString();
  	// return Response.ok(bodyResponse).build();

/*
def sb = new StringWriter()
def html1 = new MarkupBuilder(sb)
html1.doubleQuotes = true 
html1.expandEmptyElements = true 
html1.omitEmptyAttributes = false 
html1.omitNullAttributes = false 
html1.html { 
    head { 
        title ('Heading') 
        script (src: 'test.js', type: 'text/html') 
    }
    body {}
}
return Response.ok(sb.toString(), MediaType.TEXT_HTML_TYPE).build();
*/    
    // Params: release, start, type
    // type: External, Internal, format
    // model: readme (default), releasenote
    String report = queryParams.getFirst("report");
    if (!report) {report = "readme"};
    // type: External(default), Internal
    String typeDocument = queryParams.getFirst("type");
    if (!typeDocument) {typeDocument = "External"};
	// release
    String release = queryParams.getFirst("release");
    // format: text(default), html
    String format = queryParams.getFirst("format");
    if (!format) {format = "text"};
    // product: x3(default), x3p(people)
    if (!queryParams.getFirst("product")) {queryParams.putSingle("product", "X3")};
    String product = queryParams.getFirst("product");
    // keys: JIRA keys for testing 'X3-111,X3-222,...'
    String keys = queryParams.getFirst("keys");
    // filterid: filter id or if null, keep the jql programmed
    String filterId = queryParams.getFirst("filterid");
    // component: Syracuse for Syracuse fixing
    String component = queryParams.getFirst("component");
    if (!component) {component = "X3"};
    // header (External only)
    String header = queryParams.getFirst("header");
    if (!header) {header = "yes"};
    // changed files (Internal only)
    String files = queryParams.getFirst("files");
    if (!files) {files = "yes"};
	def changedFiles = (files && files.toLowerCase() == "no") ? false : true;
    // bullet
    String bullet = queryParams.getFirst("bullet");
    // debug
    if (!queryParams.getFirst("onlylevel")) {queryParams.putSingle("onlylevel", "no")};
	// For staging instance
    if (!queryParams.getFirst("staging")) {queryParams.putSingle("staging", "no")};
    // New html format
    if (!queryParams.getFirst("new")) {queryParams.putSingle("new", "no")};
    // Checks mandatory parameters
    try {
        assert (report.toLowerCase() == "readme" || report.toLowerCase() == "releasenote") : "'model' parameter should be Readme ou Releasenote";
        assert (typeDocument.toLowerCase() == "internal" || typeDocument.toLowerCase() == "external") : "'type' parameter should be Internal ou External";
        assert (product.toUpperCase() == "X3" || product.toUpperCase() == "HR") : "'product' parameter should be X3 or HR";
		if (!filterId && !keys) {
        	assert (release) : "'release' parameter is mandatory";
        }
        assert (format.toLowerCase() == "text" || format.toLowerCase() == "html") : "'format' parameter should be text ou html";
        // Cpmponents
        assert (component.toUpperCase() == "X3" || component.toLowerCase() == "syracuse" || component.toLowerCase() == "java" || component.toLowerCase() == "console" || component.toLowerCase() == "print") : "if given, 'component' parameter should be set to Syracuse, Java, console or Print";
    } catch (AssertionError e) {
        responseBuilder = Response.status(Status.NOT_FOUND).type("text/plain").entity("Something is wrong: " + e.getMessage());
        return responseBuilder.build();
    }
	queryParams.putSingle("report", report.toLowerCase());
	queryParams.putSingle("type", typeDocument.toLowerCase());
	queryParams.putSingle("format", format.toLowerCase());
	queryParams.putSingle("component", component.toUpperCase());
	queryParams.putSingle("header", header.toLowerCase());
	queryParams.putSingle("files", files.toLowerCase());
	queryParams.putSingle("onlylevel", ((String) queryParams.getFirst("onlylevel")).toLowerCase());
	queryParams.putSingle("staging", ((String) queryParams.getFirst("staging")).toLowerCase());

	// Base url
	String baseURL = getAPIJiraSageUrl(queryParams);
    // Search API syntax
    def API = "/search";
    // JQL query
    String query;
	String Jql = "project=X3";
    if (report.toLowerCase() == "readme") {
        // Issue type
        Jql += " AND issuetype in (Bug,'Entry Point')";
        // Document type
        if (typeDocument.toLowerCase() == "external") {
            Jql += " AND 'X3 ReadMe Check'='To be communicated'";
        }
        // Releases
        if (release) {
        	Jql += " AND fixVersion in versionMatch('^"+release+"')";
        }
        // JIRA keys (optionnal)
        if (keys) {
            Jql += " AND issuekey in ("+keys+")";
        } else {
            // Status
            Jql += " AND status=Done";
            // Resolution
            Jql += " AND resolution in (Done,Fixed)";
        }
        Jql += " AND issuekey not in ('X3-49153')";
        // Order
        // Fix Version/s
        Jql += " ORDER BY";
        if (component) {
        	Jql += " fixVersion DESC,";
        }
        Jql += " issuetype DESC, 'X3 Product Area' ASC, priority";
        // fields: X3 Solution Details(15118), X3 Product Area(15522), X3 Maintenances(15112), X3 Regression(15110)
        query = "&fields=summary,customfield_15118,customfield_15522,customfield_15112,issuetype,priority,customfield_15110,customfield_13411,fixVersions";
    } else if (report.toLowerCase() == "releasenote") {
        // Issue type
        Jql += " AND issuetype in (Epic, Bug)";
        // Status
        Jql += " AND status in (DONE, 'X3 ALL US DONE')";
        // TODO: change status 'X3 ALL US DONE' to Done category
        // 
        Jql += " AND (";
        Jql += "(issuetype=Epic AND 'X3 Release Note Check'='To be communicated')";	//Features
        Jql += " OR (issuetype=Bug AND 'X3 Behaviour Change'=Yes)";	// Changes
        Jql += ")";
        // Releases
        if (release) {
        	Jql += " AND fixVersion in versionMatch('^"+release+"')";
        }
        // Product
        if (((String) queryParams.getFirst("product")).toUpperCase() == "X3") {
            Jql += " AND 'X3 Product Area' not in (People)";
        } else {
            Jql += " AND 'X3 Product Area' in (People)";
        }
        // JIRA keys (optionnal)
        if (keys) {
            Jql += " AND issuekey in ("+keys+")";
        }
        // Order
        Jql += " ORDER BY 'X3 Product Area' ASC, issuetype ASC, 'X3 Components' ASC, 'X3 Legislation' DESC";

        // fields: Epic name(10801), X3 Release Note(14806), X3 Product Area(15522 -> h3), X3 Legislation(15413 -> h5), Feature(inwardIssue.fields.summary -> h6)
		//         summary, X3 Solution Details(15118)
        query = "&fields=issuetype,summary,customfield_10801,customfield_14806,customfield_15522,customfield_15413,customfield_15118,customfield_13411,issuelinks,labels,comment";
		query += "&expand=renderedFields";
    }
    if (filterId) {
        // Get the query directly from the filter given by parameter
    	JiraFilter myFilter = new JiraFilter(filterId);
        if (myFilter.isValidFilter()) {
        	Jql = myFilter.getJql();
        } else {
            responseBuilder = Response.status(Status.NOT_FOUND).type("text/plain").entity(myFilter.getErrorMessage());
            return responseBuilder.build();
        }
    }    
    // start (optionnal)
    if (queryParams.getFirst("start")) {
    	query += "&startAt="+queryParams.getFirst("start");
    }
    // maxResults
    query += "&maxResults=1000";

/*
// @see https://stackoverflow.com/questions/43808524/create-release-notes-using-jira-rest-api-in-html-format-in-groovy
	def jira = new RESTClient(baseURL);
    try { 
    	def resp = jira.get(path: "search", 
                        	contentType: "application/json", 
                        	query: query);
    	assert resp.status == 200;
    	assert (resp.data instanceof net.sf.json.JSON);
    	resp.data.issues.each {issue -> println issue.key};
    	println "Total issues: " + resp.data.total;
	} catch (groovyx.net.http.HttpResponseException e) {
    	if (e.response.status == 400) { 
        	// HTTP 400: Bad Request, JIRA JQL error
        	fail("JIRA query failed: ${e.response.data}", e) 
    	} else { 
	        fail("Failure HTTP status ${e.response.status}", e) 
	    } 
	}
*/
    // https://docs.oracle.com/javaee/7/api/javax/ws/rs/core/Response.html
    BodyResponse connection = new BodyResponse(baseURL, API, "?" + "jql="+removedSpaces(Jql) + query);
    if (connection.getHttpCode().equals(200)) {
        bodyResponse = connection.getBody();
        // Parse the response JSON
        def jsonSlurper = new groovy.json.JsonSlurper();
        Map jsonResult = (Map) jsonSlurper.parseText(bodyResponse);

        // total (issues)
        Integer total = (Integer) jsonResult.get("total");

        // Write the text of readme document
        StringWriter builder = new StringWriter();

        // Header of document
        // @see https://docs.oracle.com/javase/7/docs/api/javax/swing/JEditorPane.html
        switch (report.toLowerCase()) {
            case "readme":
            	// Header
            	if (header.toLowerCase() == "yes") {
                	builder.append(headerDocument(format));
                }
            	// Content
            	String contentTitle = "Content: Bug Fixes"
                if (component == "X3") {
					contentTitle += " and Entry Points";
                }
                builder.append(pDocument(contentTitle, format, ""));
            	// Release
            	builder.append(pDocument("Product: Sage X3", format, ""));
            	// Component
                if (component.toLowerCase() == "syracuse") {
            		builder.append(pDocument("Component: Syracuse", format, ""));
                } else if (component.toLowerCase() == "java") {
            		builder.append(pDocument("Component: Java Server", format, ""));
                } else if (component.toLowerCase() == "console") {
            		builder.append(pDocument("Component: Console", format, ""));
                } else if (component.toLowerCase() == "print") {
            		builder.append(pDocument("Component: Print Server", format, ""));
                }
				// Release
                if (release) {
        			builder.append(pDocument("Release: " + release, format, ""));
                }
            	// Document type
                if (typeDocument.toLowerCase() == "internal") {
            		builder.append(pDocument("Warning! " + typeDocument + " document", format, ""));
                }
            	break;
            case "releasenote":
				break;
        }

        // Body of document
        // Break fiels definition
        String fieldValue, breakValue;
        Map breakMap = (Map) jsonSlurper.parseText('{"breakFields":[]}');
        List breakFields = (List) breakMap.getAt("breakFields");
        switch (report.toLowerCase()) {
            case "readme":
            	// fixVersions
                if (true) {
                    breakFields.push((Map) jsonSlurper.parseText('{"item":0,"field":"fixVersions","subfield":{"name":"name","breakValue":""},"head":0,"class":""}'));
                }
            	// issuetype (Bug, Entry Point)
            	breakFields.push((Map) jsonSlurper.parseText('{"item":1,"field":"issuetype","subfield":{"name":"name","breakValue":""},"head":0,"class":""}'));
            	// X3 Product Area
            	if (component.toUpperCase() == "X3") {
					breakFields.push((Map) jsonSlurper.parseText('{"item":2,"field":"customfield_15522","subfield":{"name":"value","breakValue":""},"head":0,"class":""}'));
                }
            	break;
            case "releasenote":
            	// X3 Product Area
            	// for HR, no need to define the product area as a break, it's alone
            	int item = 0;
                if (queryParams.getFirst("product") == "X3") {
            		breakFields.push((Map) jsonSlurper.parseText('{"item":'+item+',"field":"customfield_15522","subfield":{"name":"value","breakValue":""},"head":3,"class":"g1"}'));
            		item++;
                }
            	// issuetype
				breakFields.push((Map) jsonSlurper.parseText('{"item":'+item+',"field":"issuetype","subfield":{"name":"name","breakValue":""},"head":3,"class":"changes"}'));
            	item++;
            	// X3 Components (optional)
            	//breakFields.push((Map) jsonSlurper.parseText('{"item":'+item+',"field":"customfield_13411","subfield":{"name":"value","breakValue":""},"head":5,"class":""}'));
            	//item++;
            	// X3 Legislation
				breakFields.push((Map) jsonSlurper.parseText('{"item":'+item+',"field":"customfield_15413","subfield":{"name":"value","breakValue":""},"head":5,"class":""}'));
            	// Feature
				// breakFields.push((Map) jsonSlurper.parseText('{"field":"issuelinks","subfield":{"name":"summary","breakValue":""}}'));

				if (queryParams.getFirst("new") == "no") {
                    builder.append(getReleasenoteHeader());
                }
            	break;
        }

        List issues = (List) jsonResult.get("issues");
        if (queryParams.getFirst("new") != "no") {
            // POC: call directly a function
            builder.append(createReleaseNoteReport(release, issues, breakFields, queryParams));
        } else {
		// https://docs.oracle.com/javase/8/docs/api/java/util/List.html
        issues.each {
            Map issue = (Map) it;
    		String key = issue.get("key");
			Map fields = issue.get("fields");

            // Retreive the break fields
            breakFields.eachWithIndex {breakField, idx ->
                String breakFieldName = breakField.getAt("field");
                Map breakSubfield = (Map) breakField.getAt("subfield");

				fieldValue = "";
				// Get the value property on the issue
                if (breakFieldName == "fixVersions") {
                    List fixVersions = (List) fields.getAt(breakFieldName);
                    fixVersions.each {
                        Map fixVersion = (Map) it;
                        if (fixVersion) {
                            String name = fixVersion.get("name");
                            Pattern p = Pattern.compile(release);
                            Matcher m = p.matcher(name)
                            while (m.find()) {
                                fieldValue = name;
                            }
                        }
                    }
                } else {
					Map field = fields.get(breakFieldName);
                    if (field) {
                    	fieldValue = field.get(breakSubfield.getAt("name"));
                    }
                }
                if (fieldValue && fieldValue.length() > 0) {
                    /*
                    if (field.getClass() == "class groovy.json.internal.LazyMap") {
                		fieldValue = (Map) field.get(breakSubfield.getAt("name"));
                    } else {
                        // It's not a Map class field. Perhaps a List class field
                        if (breakFieldName == "issuelinks") {
                            // 
                            List issuelinks = (List) fields.getAt(breakFieldName);
                            issuelinks.each {
                                Map link = (Map) it;
                                if (link) {
                                    // "inward": "is part of"
                                    Map type = (Map) link.get("type");
                                    if (type && type.get("inward") == "is part of") {
                                        Map inwardIssue = (Map) link.get("inwardIssue");
                                        if (inwardIssue) {
                                            Map field1 = (Map) inwardIssue.get("fields");
                                            if (field1) {
                                                fieldValue = field1.get("summary");
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } */

                    breakValue = breakSubfield.getAt("breakValue");
                    if (fieldValue != breakValue) {
						// Reset the breakValues
                        if (report.toLowerCase() == "readme" && component.toLowerCase() != "syracuse" && breakFieldName == "issuetype") {
                            breakFields[1].getAt("subfield").putAt("breakValue", "");
                        }
						// Store the breakValue
                        breakSubfield.putAt("breakValue", fieldValue);

                        // Case of issuetype value
                        String anchor = "";
                        switch (breakField.getAt("field")) {
                            case "issuetype":
                                switch (fieldValue) {
									case "Bug":
                                        if (report.toLowerCase() == "readme") {
                                            fieldValue = "Bug Fixes";
                                        } else if (report.toLowerCase() == "releasenote") {
                                            fieldValue = "CHANGES";
                                        }
                                    	break;
									case "Entry Point":
                                    	fieldValue = "Entry Points";
                                    	break;
									case "Epic":
                                    	fieldValue = "FEATURES";
                                    	break;
                                }
                            	break;
                            case "customfield_15522":	// X3 Product Area
                        		anchor = "MIS_"+(idx+1)*10+"_"+fieldValue;
                            	break;
                        }
                        // Write the value of the break field
						Integer level = ((String) breakField.getAt("head")).toInteger();
						String hclass = breakField.getAt("class");
                        builder.append(hDocument(fieldValue, level, anchor, hclass, format));
                    }
                }
            }

            // Write the body issue
            switch (report.toLowerCase()) {
                case "readme":
        			builder.append(getReadmeBody(key, fields, queryParams));
                	break;
                case "releasenote":
					Map renderedFields = issue.get("renderedFields");
        			builder.append(getReleasenoteBodyIssue(key, fields, renderedFields, queryParams));
                	break;
            }
		};
        }

        // Footer of document
        builder.append(footerDocument(format));

		// Store the builder text to the bodyResponse
        switch (format) {
            case "html":
        		typeResponse = "text/html";
            	// POC - Copy the html file (https://www.journaldev.com/861/java-copy-file) 
            	// https://www.mkyong.com/java/how-to-convert-inputstream-to-file-in-java/
				InputStream inputStream = new ByteArrayInputStream(builder.toString().getBytes());
            	File dest = new File("\\\\10.169.140.21\\pieces_jointes\\HTML\\CFF\\FCT\\RELNOTE_JIRA.htm");
            /*
            	OutputStream os = new FileOutputStream(dest);
            	
            	byte[] buffer = new byte[1024];
        		int length;
        		while ((length = inputStream.read(buffer)) > 0) {
            		os.write(buffer, 0, length);
        		} */
				// or
            	//def success = copy(inputStream, "\\\\ayvqsuiviprd\\pieces_jointes\\HTML\\CFF\\FCT");
            	/*
            	OutputStream outputStream = new FileOutputStream(new File("C:\\temp\\RELNOTE_JIRA.htm"));
            	int read = 0;
				byte[] bytes = new byte[1024];
				while ((read = inputStream.read(bytes)) != -1) {
					outputStream.write(bytes, 0, read);
				} */
            	/*
				FileChannel sourceChannel = null;
            	FileChannel destChannel = null;
				try {
					sourceChannel = new FileInputStream(dest).getChannel();
        			destChannel = new FileOutputStream(dest).getChannel();
        			destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
       			} finally {
           			sourceChannel.close();
           			sourceChannel.close();
                } */
            	break;
            case "text":
        		typeResponse = "text/plain";
            	break;
        }

		/* good example of code with html format
        // StringWriter writer1 = new StringWriter();
        myBuilder.a(href: "http://www.example.com", "bla bla bla")
        myBuilder.p("bla bla bla");
        // bodyResponse = newbuilder.getMkp().toString();
        bodyResponse = builder.toString();
        typeResponse = "text/html";
        // return Response.ok(sb.toString(), MediaType.TEXT_HTML_TYPE).build();
        */
        
        responseBuilder = Response.ok(builder.toString()).type(typeResponse);

    } else {
		responseBuilder = Response.status(connection.getHttpCode()).type("text/plain").entity("Oops! Error "+connection.getHttpCode()+". "+connection.getErrorMessage());
        // throw new RuntimeException("HTTP GET Request Failed with Error code: " + connection.getHttpCode());
    }
	return responseBuilder.build();
}

def callGet(String url) {
    new groovy.json.JsonSlurper().parseText(url.toURL().getText())
}

def removedSpaces(String inputString) {
    String outputString = "";

    int i;
    for (i=0; i < inputString.length(); i++) {
        if (inputString[i] == " ") {
            outputString += "%20";
        } else {
            outputString += inputString[i];
        }
    }
    return outputString;
}

public static void repeat(StringBuilder stringBuilder, String s, int times) {
    if (times > 0) {
        repeat(stringBuilder.append(s), s, times - 1);
    } 
} 
 
public static String repeat(String s, int times) {
    StringBuilder stringBuilder = new StringBuilder(s.length() * times);
    repeat(stringBuilder, s, times);
    return stringBuilder.toString();
}

public static String getBullet(priority, String defaultBullet) {
    def priorities = [Minor:"-", Major:"*", Critical:"^", Blocker:"!"];
    if (priority) {
    	return priorities[priority];
    } else {
    	return defaultBullet;
    }
}

public static String getAPIJiraSageUrl(MultivaluedMap queryParams) {
    return getJiraSageUrl(queryParams) + "/rest/api/latest";
} 

public static String getJiraSageUrl(MultivaluedMap queryParams) {
    String baseURL;
    if (queryParams.getFirst("staging") == "no") {
        baseURL = "https://jira-sage.valiantyscloud.net";
    } else {
        baseURL = "https://jira-sage-staging.valiantyscloud.net";
    }
    return baseURL;
} 

public static String getReadmeBody(String key, Map fields, MultivaluedMap queryParams) {
	String format = queryParams.getFirst("format");
	StringBuilder builder = new StringBuilder();
    def markupBuilder, writer;
    if (format == "html") { // if (format == "html") {
    	writer = new StringWriter();
        markupBuilder = new groovy.xml.MarkupBuilder(writer);
    }

    if (fields) {
        // summary
        String summary = fields.get("summary");
        // priority
        String priorityValue = ((Map) fields.get("priority")).get("name");
        // X3 Solution details
        String solution = fields.get("customfield_15118");
        // X3 Maintenances
        String maintenances = fields.get("customfield_15112");
    	def maintenancesVersion;
        if (maintenances && !maintenances.isEmpty()) {
            // String[] array = maintenances.split("-", -1);
            List maintenancesAllVersions = new ArrayList<>(Arrays.asList(maintenances.split("- ")));
            if (maintenancesAllVersions && !maintenancesAllVersions.isEmpty()) {
                /* Groovy: Groovy has findAll method to find all the elements matching the predicate. The predicate is specified using a Closure.
						Groovy closures use an implicit parameter name ‘it’ and mentioning the parameter type is completely optional. */
                // Filter maintenances on the good product and version
                maintenancesVersion = maintenancesAllVersions.findAll {item -> item.indexOf("X3 V11") >= 0}
                /*if (maintenancesVersion && maintenancesVersion.size() > 0) {
    				List maintenanceList;
                    maintenancesVersion.each {if (it) {maintenanceList = new ArrayList<>(Arrays.asList(it.split(", ")))}}
                }*/
            }
        }

        // Write informations
		if (queryParams.getFirst("type") == "internal") { //if (typeDocument.toLowerCase() == "internal") {
            // format for Internal readme
        	builder.append(pDocument("", format, ""));
            // write the 'summary' field
        	builder.append(pDocument(getBullet(priorityValue, "") + " $key - $summary", format, ""));
            // write the 'X3 Solution details' field
            if (solution) {
                // "^[\\r?\\n]", "(\\r*\\n)\$"
                def properSolution = solution.replaceAll("(\\r*\\n)\$", "");
        		builder.append(pDocument(properSolution, format, ""));
                // $|^\s*\r?\n
                //builder.append(pDocument(solution.replaceAll(System.getProperty("line.separator"), ""), format, ""));
            } else {
            	builder.append(pDocument("<no solution given for this ticket>", format, ""));
            }
            // Add the X3 maintenances numbers
            if (false && maintenancesVersion && !maintenancesVersion.isEmpty()) {
                builder.append("Maintenances: ");
                def pattern = /\[[0-9]*\|/
                maintenancesVersion.each {
                    def maintenancesNumber = it =~ pattern;
                    maintenancesNumber.each {
        				builder.append(pDocument(it.toString()+",", format));
                    };
                };
            }
        } else {
            // format for External readme
        	builder.append(pDocument("", format, ""));
            // write the 'summary' field
        	builder.append(pDocument(getBullet(null, "-") + " $summary [JIRA#$key]", format, ""));
            // write the 'X3 Solution details' field
            if (solution) {
            	builder.append(pDocument(solution, format, ""));
            } else {
            	builder.append(pDocument("<no solution given for this ticket>", format, ""));
            }
        }

        // Get the files updated on Github (see properties on JIRA API)
		if (!(queryParams.getFirst("files") == "no")) { //if (changedfiles) {
            ChangedFiles commits = new ChangedFiles(key);
            if (commits.asChangedFiles()) {
                def files = commits.getFiles();
                if (files) {
                    def numberFiles = files.size();
                    builder.append(pDocument("$numberFiles changed files:", format, ""));
                    files.eachWithIndex() {item, idx ->
                        builder.append(pDocument(". " + item, format, ""));
                    }
                }
            }
        }
    }

    return builder.toString();
}

public static String createReleaseNoteReport(String releaseName, List issues, List breakFields, MultivaluedMap queryParams) {

	def releases = [new Release(name:'2018 R7', month:"November 2018", href:"MIS_2018R7"),
        			new Release(name:'2018 R6', month:"October 2018", href:"MIS_2018R6"),
                    new Release(name:'2018 R5', month:"September 2018", href:"MIS_2018R5"),
                    new Release(name:'2018 R4', month:"August 2018", href:"MIS_2018R4"),
        			new Release(name:'2018 R3', month:"July 2018", href:"MIS_2018R3"),
                    new Release(name:'2018 R2', month:"May 2018", href:"MIS_2018R2"),
                    new Release(name:'2018 R1', month:"March 2018", href:"MIS_2018R1"),
                    new Release(name:'2017 R6', month:"December 2017", href:"MIS_2017R7"),
                    new Release(name:'2017 R4', month:"October 2017", href:"MIS_2017R6"),
                    new Release(name:'2017 R3', month:"August 2017", href:"MIS_2017R4"),
                    new Release(name:'2017 R2', month:"June 2017", href:"MIS_2017R2"),
                    new Release(name:'2017 R1', month:"May 2017", href:"MIS_2017R1")
                   ];
	def release = releases.find {it.getName() == releaseName};

	def productAreas = [new ProductArea(name:'Finance', href:"MIS_FINAN"),
                       	new ProductArea(name:'Distribution', href:"MIS_DISTR"),
                       	new ProductArea(name:'Manufacturing', href:"MIS_MANUF"),
                       	new ProductArea(name:'Projects', href:"MIS_PROJ")];

	def writer = new StringWriter();
    // MarkupBuilder markupBuilder = new MarkupBuilder(writer);
	/* - POC on writing directly on file
	File output = new File("\\\\10.169.140.21\\pieces_jointes\\HTML\\CFF\\FCT\\RELNOTE_JIRA.htm");
	def builder = new MarkupBuilder(new FileWriter(output));
    - End of POC */
    def builder = new MarkupBuilder(new IndentPrinter(new PrintWriter(writer), ""));
	def mkp = builder.getMkp();
	builder.setOmitEmptyAttributes(true);
    // def mkp = new MarkupBuilderHelper(builder);
    builder.html {
        head {
            meta ('http-equiv': 'Content-Type', content: 'text/html; charset=utf-8')
            title ("getReport(draft)")
            link (rel: "styleSheet", type:"text/css", media: "all", href: "http://212.67.43.50/erp/99/release-note-70/ADXHelp_main.css")
            /*
            style (media:"all"){
        		mkp.yieldUnescaped("http://212.67.43.50/erp/99/release-note-70/ADXHelp_main.css".toURL().text)
      		}*/
            writeStyle (builder);
        }
        // Inline CSS
        body (id: "adx-help") {
            if (queryParams.getFirst("type") == "internal") {
                button (type: "button", onclick: "alert('Report was copied to ...')", "Copy to ...")
                // button (type: "button", onclick: "myFunction()", "Copy to ...")
            }
			div (id: "container") {
                div (id: "linkList") {
                    div (id: "linkList2") {
                        div (id: "lmainmenu") {
                            // List of releases
                            ul {
                                li {
                                    releases.each {
                                        def curRelease;
                                        if (queryParams.getFirst("type") == "internal") {
                                            curRelease = it.getName();
                                        } else {
                                            curRelease = it.getMonth();
                                        }
                                        a (href: "#"+it.getHref(), curRelease)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            div (id: "topNBLinks", style: "hidden") {
                a (href: "../index.htm", "Index")
            }
            div (id: "pageHeader") {
                h1 (class: "g1") {
                    span ("RELEASE")
                }
                h1 (class: "h1_subtitle", "NOTES")
            }
            div (id: "supportingText") {
                p "This page describes new functionality introduced for this and previous releases of Enterprise Management. Check this page for announcements about new or updated features and issues."
                p {
                    b "Sage X3"
                    mkp.yield (" is now ")
                    b "Enterprise Management"
                    mkp.yield (". This name change was part of the recent launch of Sage Business Cloud, the one and only cloud platform designed for every stage of customer business growth.")
                }
                p {
                    mkp.yield ("Visit ")
                    a (href:'http://www.sage.com', target:"_blank", "sage.com")
                    mkp.yield (" to learn more about what ")
                    b "Sage Business Cloud"
                    mkp.yield (" can do for you.")
                }

				// Begin given release
                mkp.comment ("Begin ${release.getName()} release")
                div (class: "stdBloc") {
                    div (class: "links") {
                        h3 (class: "g1") {
                            span {}
                        }
                        // List of Product Area
                        ul {
                            def href, name;
                            productAreas.each {
                                href = it.getHref();
                                name = it.getName();
                                li {a (href: "#"+href, name)}
                            }
                        }
                    }
                    // Release
                    def curRelease;
                    if (queryParams.getFirst("type") == "internal") {
                        curRelease = release.getName();
                    } else {
                        curRelease = release.getMonth().toUpperCase();
                    }
                    h3 (class: "version", id: release.getName(), curRelease)
                    /*
                    h3 (class: "version", id: release.getName()) {
                        mkp.yield (release.getMonth().toUpperCase())
                    }*/
                    // add Readme link (if necessary)
                    p {
                        mkp.yield ("Refer to the ")
                        a (href: "/README_ENG_2017R2.txt", target: "_blank", "Readme")
                        // mkp.comment ("insert link here")
                        mkp.yield (" document for updates on bug fixes.")
                    }

                    // Loop on each issues
					// hr ()	// add horizontal line
                    String key;
                    Map issue, fields, renderedFields;
                    issues.each {
                        issue = (Map) it;
                        fields = issue.get("fields");

                        // break fields
						breakFields.eachWithIndex {breakField, idx ->
                        	if (writeHeaders (builder, breakField, fields, queryParams, release, productAreas)) {
                                // Reset all descendant level after writing the break value
                                resetBreakFieldsValue (breakFields, idx);
                            }
						}

						// issue summary
                        key = issue.get("key");
                        renderedFields = issue.get("renderedFields");
                        writeSummary (builder, writer, key, fields, renderedFields, queryParams);
                    }
                } // End given release

                div (id: "footer") {
					mkp.yield ("© ")
                    a (href: "http://www.sagenorthamerica.com", title: "Site officiel Sage", "Sage")
					mkp.yield ("1999-2018")
                }
			}
        }
    }
	return '<!DOCTYPE html>' + writer.toString()
}

public static void writeStyle(MarkupBuilder builder) {
    builder.style (type:"text/css", '''
iframe {
    min-height: 380px;
    color: white;
}

body, html, div {

    margin: 0;
    padding: 0;
	color: #3c424f;
    font-family: "Lato","Helvetica Neue",Arial,sans-serif;
    font-size: 14px;
}
body {
    background: url("lbg.png") repeat-y fixed 0 0 #FFFFFF;
}
INPUT {
    background-color: #009FDA;
    border: 1px none;
    color: #FFFFFF;
    font-weight: bold;
}
p {
    /*padding-left: 8px;*/
    padding-right: 8px;
}

.p2 {
	padding-right: 8px;
	padding : 1px 0px 1px 0px;
	-webkit-margin-before: 4px;
    -webkit-margin-after: 4px;
    -webkit-margin-start: 0px;
    -webkit-margin-end: 0px;
	line-height:16px;
}
div {
}
input {
    font-size: 9pt;
    margin-top: 5px;
    padding: 5px;
}
.version {
    background-color: #990069;
    border-radius: 4px;
    color: #fff;
    display: inline-block;
    font-size: 20pt;
    font-weight: normal;
    margin-bottom: 0;
    padding: 5px 20px;
	margin-top:70px;
}
.features {
     background-color: #41a940;
    border-radius: 50px;
    color: #fff;
    display: inline-block;
    font-size: 10pt;
    font-weight: normal;
    margin-bottom: 0;
    padding: 5px 15px;
    text-transform: uppercase;
}
.changes {
     background-color: #146E96;
    border-radius: 50px;
    color: #fff;
    display: inline-block;
    font-size: 10pt;
    font-weight: normal;
    margin-bottom: 0;
    padding: 5px 15px;
    text-transform: uppercase;
}
h1 {
    color: #51534a;
    font-size: 20pt;
    font-weight: normal;
    margin-bottom: 0;
}
h2 {
    background: none repeat scroll 0 0 #4D4F53;
    color: #FFFFFF;
    margin-bottom: 0;
    padding: 5px;
}
h3 {
    border-bottom: 1px solid #DDDDDD;
    color: #3c424f;
}
h4 {
    background: none repeat scroll 0 0 #4D4F53;
    color: #FFFFFF;
    font: bold 10pt "arial";
    margin-top: 30px;
    padding: 5px 15px;
}
bor {
    color: #41A940;
    font-weight: normal;
    padding-left: 8px;
}
h6 {
    font-weight: bold ;
	font-size: 9pt;
    padding-left: 7px;
}
h1.g1 {

    margin-bottom: 0;
}
h2.g1 {
    font-size: 14pt;
    letter-spacing: 1px;
    margin-bottom: 0;
}
h3.g1 {
   border-bottom: 0 solid #dddddd;
    color: #C8006E;
    font-size: 2.3em;
    font-weight: normal;
	margin-bottom: 15px;
    margin-top: 60px;
}
h4.g1 {
    background: none repeat scroll 0 0 #4D4F53;
    border: 0 none;
    color: #FFFFFF;
    font: 1.4em "arial";
    padding: 10px 38px 10px 15px;
}
h4.bloctitle {
    background: none repeat scroll 0 0 #4D4F53;
    border: medium none;
    color: #FFFFFF;
    font: bold 10pt/30px arial;
}
h5.g1 {
    border-bottom: 1px dashed;
    color: #41A940;
    font: 1.4em "arial";
}
h6.g1 {
    font: bold 10pt "arial";
}
a {
    color: #255bc7;
    cursor: pointer;
    text-decoration: none;
}
  a:hover {
        color: #1963f6;
    text-decoration: underline;
}
a:visited {
    color: #1963f6;
    text-decoration: underline;
}

ul {
    margin: 0 0 0 5px;
}
ul.std {
    font: 10pt/2.5ex "arial",sans-serif;
    letter-spacing: normal;
    list-style: disc outside url("");
    margin-bottom: 2px;
    margin-left: 5px;
    padding-top: 2px;
}
ul.g1 {
    font: 10pt/2.5ex "arial",sans-serif;
    letter-spacing: normal;
    list-style: disc outside url("bullet_brique.gif");
    margin-bottom: 15px;
    margin-left: 5px;
    margin-top: 15px;
    padding-top: 0;
}
ol {
    margin: 0 0 0 10px;
}
li {
    line-height: 3ex;
    list-style-type: none;
    margin-bottom: 2px;
    /*margin-left: 18px;*/
    padding-top: 2px;
	list-style-type: square;
    list-style-position: inside;
    text-indent: -1.5em;
    padding-left: 32px;
}
u{
	font-size: 1.2em;
    font-weight: bold;
    line-height: 24px;
    text-decoration: none;
}
u li {
    list-style-type: none;
    margin-left: 6px;
	padding-left: 26px; !important;
}
li.liste3:before {
	content: "? "; /* caractère UTF-8 */
	padding-right:8px;
}
u li:before {
	content: ""; /* caractère UTF-8 */
}
#lmainmenu li::before {
    content: "";
}
.links li::before {
    content: "";
}
.liste3{
	padding-left: 58px;
	list-style-type: none;
	line-height: 2.5ex; !important; 
	text-indent: -2em !important;
}
.liste4{
	padding-left: 86px;
	list-style-type: disc;
	line-height: 2ex; !important; 
	text-indent: -2em !important;
}
video{
max-width: 70%;
	
}
#container {
    background: url("topbg.png") repeat-x scroll 0 0 rgba(0, 0, 0, 0);
    border-right: 1px solid #223300;
    border-top: 1px solid #4D4F53;
    margin: 0;
    padding: 0;
}
#topNavBar {
	display:block;
    margin: 7px 0 0;
    padding: 0;
    position: relative;
	display: none;
}
#topNavBar img {
    border: 0 none;
	padding-left: 20px;
}
#topNavBar p {
    padding: 0;
}
#topTabOpen {
    position: absolute;
    top: -50px;
}
#tabOpen {
    background: none repeat scroll 0 0 rgba(250, 250, 250, 0.2);
    display: table-cell;
    padding: 0 10px;
    text-align: center;
}
#tabClose {
    background: none repeat scroll 0 0 rgba(250, 250, 250, 0.2);
    display: table-cell;
    padding: 10px;
    text-align: center;
    width: 60px;
}
#topNBHome {
    background: url("s-site-sage.png") no-repeat scroll 0 0 rgba(0, 0, 0, 0);
	display:block;
    height: 24px;
    left: 0;
    position: absolute;
    width: 155px;
	display: none;
}
#topNBLinks {
    border-right: 0 solid #ABBC47;
    border-top: 0 solid #ABBC47;
    padding-right: 2em;
	display: none;
} 
#topNBLinks p {
    color: #FFFFFF;
    font-weight: bold;
    text-align: right;
}
#topNBLinks a:link {
   border: 1px solid;
    border-radius: 2px;
    color: #ed1c5f;
    padding: 4px 12px;
    text-decoration: none;
}
#topNBLinks a:visited {
    color: #E0E1DD;
    text-decoration: none;
}
#topNBLinks a:hover {
    background: #ed1c5f;
	color:#FFFFFF;
}
#topNBPath {
    color: #FFFFFF;
    font: 10pt "arial";
    position: absolute;
 top: 100px;
    width: 100%;
	display: none;
}
#topNBPath p {

    color: #767a83;
    font: 10pt/20px "arial";
   margin-left: 220px;
    padding-left: 33px;
    position: relative;
}
#topNBPath a:link {
    color: #FFFFFF;
    text-decoration: none;
}
#topNBPath a:visited {
    color: #FFDDDD;
    text-decoration: none;
}
#topNBPath a:hover {
    color: #FFFFFF;
    text-decoration: underline;
}
#pageHeader {
    margin: 0;
    padding: 0;
    width: 100%;
}
#pageHeader h1 {
    font-size: 4em;
    margin: 0 0 0 220px;
    padding: 30px 0 0px 30px;
	line-height:46px;
}
.h1_subtitle {
	padding-top : 0px !important;
}
#pageHeader h1 span{
	font-weight: bold;
}	
#pageHeader h2 {
    display: none;
}
#intro {
    margin: 0;
    padding: 0;
}
#presentation {
    background-color: rgba(0, 0, 0, 0.05);
    margin: 15px 15px 15px 180px;
    padding: 2px 5px 15px;
}

#presentation p {
    text-align: justify;
}

div.stdBloc {
    border-bottom: 1px solid #ddd;
    padding: 0 0 50px;
}
div.stdBloc:last-child {
    border-bottom: medium none;
}

#supportingText {
    margin: 10px 0 0 220px;
    padding: 0 30px;
    text-align: justify;
	overflow: auto;
	overflow-y: hidden;
	-ms-overflow-y: hidden;
}
#footer {
       background: #262a33 none repeat scroll 0 0;
    bottom: 0;
    clear: both;
   color: #b1b3b9;
    left: 0;
    padding: 5px;
    position: fixed;
    text-align: center;
    width: 100%;

}
#footer a:link, #footer a:visited {
   color: #b1b3b9;

}
div.stdBloc > .links {
    color: #00A1DE;
    font: 9pt "arial",sans-serif;
    left: 7px;
    margin: 180px 0 0;
    position: absolute;
    width: 145px;
	padding-left: 20px;
}
div.links h3 {
    border-top: 1pt solid rgba(0, 0, 0, 0);
    color: #00A1DE;
    font: bold 12pt "arial";
    letter-spacing: 1px;
    margin-bottom: 2px;
}
div.links ul {
    color: #00A1DE;
    margin: 0;
    padding: 0 6px;
}
div.links li {
   
    display: block;
    
    list-style-type: none;
    margin: 0;
    padding: 5px 0 6px 10px;
}
div.links li a {
    color: #FFFFFF;
    display: block;
    font-weight: normal;
    text-align: left;
}
#linkList {
 /*  color: #00A1DE;
    position: absolute;
    top: 200px;
    width: 200px;*/
    background-color: #16242C;
    left: 0;
    margin-right: 20px;
    min-height: 100vh;
    min-width: 200px;
    position: fixed;
}
#linkList2 {
	padding-left:20px;
}

#linkList2 h3.select span {
    display: none;
}
#lmainmenu {
	background-color: #16242C;
    padding-top: 30px;
}
#lmainmenu ul {
    padding: 0 10px 5px 20px;
}
#lmainmenu li {
    display: block;
    list-style-type: none;
    padding: 5px 20px 5px 0;
}
#lmainmenu li a {
    display: block;
    
    text-align: left;
}
#lmainmenu li a.c {
    color: #00A1DE;
    display: inline;
    font-weight: normal;
    text-decoration: none;
}
#lmainmenu li a {
    color: #fff;
    display: block;
    font-weight: lighter;
    letter-spacing: 0.05em;
    text-align: left;
}
#lmainmenu li a.c:hover {
    
    text-decoration: underline;
}
TABLE.std1 {
    border-top-width: 0;
    font-size: 10pt;
    margin-left: 0;
    padding: 1px 3px 4px;
    text-align: center;
    width: 100%;
}
TABLE.std1 TD {
    background-color: #FFFFFF;
    border: 1px solid #C8C8C8;
    font-size: 10pt;
    padding: 3px;
    text-align: left;
}
TH.std1ColOdd p {
    color: #FFFFFF;
    font-size: 9pt;
    font-weight: bold;
    margin: 0.4em;
    padding: 0;
    text-align: center;
    vertical-align: sub;
}
TH.std1ColEven p {
    background-color: #779244;
    color: #FFFFFF;
    font-size: 10pt;
    font-weight: bold;
    padding-left: 0.3em;
    padding-right: 0.3em;
    text-align: center;
    vertical-align: sub;
}
TD.std1OddRowEvenCol p {
    background-color: #F4F8FB;
    border-right: 0 solid #B2CDE5;
    font-size: 10pt;
}
TD.std1EvenRowEvenCol p {
    background-color: #ECECEC;
    font-size: 10pt;
}
TD.std1OddRowOddCol p {
    background-color: #F4F8FB;
    font-size: 10pt;
}
TD.std1EvenRowOddCol p {
    background-color: #ECECEC;
    font-size: 10pt;
}
TABLE.fld1 {
    font-size: 10pt;
    text-align: left;
    width: 100%;
}
TABLE.fld1 td {
    padding: 1px 1px 1px 3px;
}
TABLE.fld1 ul {
    margin: 0 0 0 5px;
    padding: 0;
}
TABLE.fld1 ul.std {
    font: 10pt/2.5ex "arial",sans-serif;
    letter-spacing: normal;
    list-style: disc outside url("");
    margin-bottom: 0;
    margin-left: 5px;
    padding-top: 0;
}
TABLE.fld1 ul.g1 {
    font: 10pt/2.5ex "arial",sans-serif;
    letter-spacing: normal;
    list-style: disc outside url("bullet_brique.gif");
    margin-bottom: 15px;
    margin-left: 5px;
    margin-top: 15px;
    padding-top: 0;
}
TABLE.fld1 li {
    font: 10pt/2.5ex "arial",sans-serif;
    letter-spacing: normal;
    list-style: disc outside none;
    margin-bottom: 0;
    margin-left: 30px;
    padding-top: 0;
}
TABLE.fld1 li.fld {
    font: bold 10pt/2.5ex "arial",sans-serif;
    letter-spacing: normal;
    list-style: disc outside url("bullet_brique.gif");
    margin-bottom: 0;
    margin-left: 15px;
    margin-top: 10px;
    padding-top: 0;
}
div.ddesc {
    background-color: rgba(0, 0, 0, 0.02);
    border: 1px solid #AAAAAA;
    border-radius: 3px;
    display: none;
    margin: 60px 0;
}
div.ddesc p {
    padding: 1px 6px 5px 15px;
}
div.bdesc {
    background: url("puce-titres.gif") no-repeat scroll 0 0 rgba(0, 0, 0, 0);
    color: #FF5800;
    font: 11pt "arial";
    left: 201px;
    margin: 10px 0 0 -16px;
    padding: 0 0 10px 18px;
    position: absolute;
}
div.bdesc:hover {
    cursor: pointer;
    text-decoration: underline;
}
div.bdesc p {
    margin: 0;
    padding: 0;
}
div.dfields {
    background-color: rgba(0, 0, 0, 0.02);
    border: 1px solid #AAAAAA;
    border-radius: 3px;
    display: none;
    margin: 60px 0 0;
    padding: 0;
}
div.dfields p {
    padding: 1px 1px 1px 20px;
}
div.bfields {
    background: url("puce-titres.gif") no-repeat scroll 0 1px rgba(0, 0, 0, 0);
    color: #FF5800;
    font: 11pt "arial";
    left: 201px;
    margin: 10px 0 0 -16px;
    padding: 0 0 10px 18px;
    position: absolute;
    vertical-align: top;
}
div.bfields:hover {
    cursor: pointer;
    text-decoration: underline;
}
div.bfields p {
    margin: 0;
    padding: 0;
}
#cache {
    display: none;
}
#hdico {
    background-color: #64902B;
    border-color: #99B467 #99B467 #013D23 #013D23;
    border-style: solid;
    border-width: 1px;
    color: #CDE0A8;
    font-family: arial;
    font-size: 10pt;
    font-weight: bold;
    padding-left: 0.3em;
    padding-right: 0.3em;
    text-align: center;
    vertical-align: sub;
}
#hdicos {
    background-color: #013D23;
    border-color: #99B467 #99B467 #013D23 #013D23;
    border-style: solid;
    border-width: 1px;
    color: #B7C000;
    font-family: arial;
    font-size: 10pt;
    font-weight: bold;
    text-align: center;
}
#ldico {
    border-bottom: 1px solid #BBBBBB;
    border-left: 1px solid #BBBBBB;
    color: #675C53;
    font-family: arial;
    font-size: 10pt;
    text-align: center;
}
#ldicodis {
    background: url("disabledtb.gif") repeat scroll 0 0 rgba(0, 0, 0, 0);
    border-bottom: 1px solid #BBBBBB;
    border-left: 1px solid #BBBBBB;
    color: #675C53;
    font-family: arial;
    font-size: 10pt;
}
#ldico2 {
    border-bottom: 1px solid #BBBBBB;
    border-left: 1px solid #BBBBBB;
    color: #000000;
    font-family: Arial;
    font-size: 7pt;
    font-weight: normal;
    text-align: center;
}
#ldicos {
    background-color: #F4FCE6;
    border-bottom: 1px solid #BBBBBB;
    border-left: 1px solid #BBBBBB;
    border-right: 1px solid #BBBBBB;
    color: #675C53;
    font-family: arial;
    font-size: 10pt;
    font-weight: normal;
    text-align: center;
}
#lzone {
    border-bottom: 1px solid #BBBBBB;
    border-left: 1px solid #BBBBBB;
    color: #000000;
    font-family: Arial;
    font-size: 10pt;
    font-weight: normal;
    padding-left: 8px;
    text-align: left;
}
#lzones {
    border-bottom: 1px solid #BBBBBB;
    border-left: 1px solid #BBBBBB;
    border-right: 1px solid #BBBBBB;
    color: #000000;
    font-family: Arial;
    font-size: 10pt;
    font-weight: normal;
    padding-left: 8px;
    text-align: left;
}
span.instruction1 {
    color: #008000;
    font-family: "Courier New";
    font-weight: bold;
}
span.commentaire1 {
    color: #696969;
    font-family: "Courier New";
}
span.syntaxe1 {
    color: #008000;
    font-family: "Courier New";
}
span.motcle1 {
    color: #008000;
    font-family: "Courier New";
}
span.parametreinstruction1 {
    color: #000000;
    font-family: "Courier New";
    font-style: italic;
}
span.element1 {
    color: #000000;
    font-family: "Courier New";
    font-style: italic;
}
h4.bloctitle p {
    line-height: 20px;
    margin: 0;
    padding: 0;
}
h5 {
    font-size: 20pt;
    font-weight: normal;
    margin: 40px 0 0;
    padding: 0 0 10px 1px;
}

.stdBloc > h5 {
    font-size: 11pt;
    margin: 40px 0 0;
    padding: 0 !important;
}
.fld1 h5 {
    font-size: 10pt;
    padding-left: 20px;
    text-decoration: underline;
}
.std1ColOdd {
    background: none repeat scroll 0 0 #9A9B9C;
    padding: 10px;
    text-transform: uppercase;
}
#container > #pageHeader h5 {
    width: 100%;
}
#pageHeader h5 {
    color: #41A940;
    font: 18pt "arial";
    margin: 30px 0 30px 155px;
    padding: 60px 30px 0;
}
div#container table {
    font-size: 1em;
}
.highlight {
    background-color: #FFEF67;
    color: #434343;}
}	
''')
}

public static boolean writeHeaders(MarkupBuilder builder, Map breakField, Map fields, MultivaluedMap queryParams, Release release, List<ProductArea> productAreas) {
    String breakFieldName = breakField.getAt("field");
    Map breakSubfield = (Map) breakField.getAt("subfield");

	// Get the last breakfield value
    String breakValue = breakSubfield.getAt("breakValue");
    // Get the field value
    String fieldValue = getFieldValue(breakFieldName, breakSubfield, fields, release.getName())

	if (fieldValue != breakValue) {
        // Update the last break value
        breakSubfield.putAt("breakValue", fieldValue);

		// Write the value of the break field
        Integer level = ((String) breakField.getAt("head")).toInteger();
        String headerClass = breakField.getAt("class");
        // Search the href of current 'X3 Product Area'
        String anchor;
        switch (breakFieldName) {
            case "customfield_15522":
            	// Get the anchor
                ProductArea productArea = productAreas.find {it.getName() == fieldValue};
                if (productArea) {
                    anchor = productArea.getHref();
                }
                break;
            case "issuetype":
            	// Change Bug to CHANGES and Epic to FEATURES
                if (fieldValue == "Bug") {
                    fieldValue = "CHANGES";
                } else if (fieldValue == "Epic") {
                    fieldValue = "FEATURES";
                }
            	break;
            default :
            	anchor = "";
        }
		switch (level) {
            case 1:
            builder.h1 (class: headerClass, id: anchor, fieldValue)
            break;
            case 2:
            builder.h2 (class: headerClass, id: anchor, fieldValue)
            break;
            case 3:
            builder.h3 (class: headerClass, id: anchor, fieldValue)
            break;
            case 4:
            builder.h4 (class: headerClass, id: anchor, fieldValue)
            break;
            case 5:
            // builder.h5 (class: headerClass, id: anchor, fieldValue)
            builder.u {
            	h5 (class: headerClass, id: anchor, fieldValue)
            }
            break;
        }
        return true;
	} else {
        return false;
    }
}

/*
Reset break fields values
*/
public static void resetBreakFieldsValue(List breakFields, int idx) {
    def followingBreaks = breakFields.findAll {((int) it.getAt("item")) > idx};
        if (followingBreaks) {
            followingBreaks.each {
                Map breakSubfield = (Map) it.getAt("subfield");
                if (breakSubfield) {
                    // p ("!Reset $idx " + it.getAt("field") +"/"+ breakSubfield.getAt("breakValue"))
                    breakSubfield.putAt("breakValue", "#");
                }
            }
        } // end Reset
}


/*
Get the value property of a field
*/
private static String getFieldValue(String breakFieldName, Map breakSubfield, Map fields, String release) {
    String fieldValue;
    switch (breakFieldName) {
        case "fixVersions":
            List fixVersions = (List) fields.getAt(breakFieldName);
            fixVersions.each {
                Map fixVersion = (Map) it;
                if (fixVersion) {
                    String name = fixVersion.get("name");
                    if (name.indexOf(release) >= 0) {
                        fieldValue = name;
                        return;
                    }
                }
            }
        	break;
        default :
            Map field = fields.get(breakFieldName);
            if (field) {
                fieldValue = field.get(breakSubfield.getAt("name"));
            }
    }
    return fieldValue;
}

public static void writeSummary(MarkupBuilder builder, StringWriter writer, String key, Map fields, Map renderedFields, MultivaluedMap queryParams) {
	def wikiNote = (queryParams.getFirst("staging") == "yes");

	if (fields) {
        // issuetype
        String issuetype = ((Map) fields.get("issuetype")).get("name");
		// summary and note
        String summary, releaseNote;
        if (issuetype == "Epic") {
            // for Epic
        	summary = fields.get("customfield_10801");	// Epic name
            if (wikiNote) {
        		releaseNote = renderedFields.get("customfield_14806");
            } else {
        		releaseNote = fields.get("customfield_14806");
            }
        } else {
            // for Bug (behavior change)
        	summary = fields.get("summary");
        	releaseNote = fields.get("customfield_15118");	// X3 Solution Detail
        }
        // Comments (for POC, get the comment as the release note field)
        if (releaseNote == "See comment") {
            Map comment = renderedFields.get("comment");
            if (comment) {
                List comments = (List) comment.getAt("comments");
                if (comments) {
                    releaseNote = comments[0].getAt("body");
                    wikiNote = true;
                }
            }
        }

        // Write into builder
		String url = getJiraSageUrl(queryParams);
		builder.div (key: "$key"){
            if (queryParams.getFirst("type") == "internal") {
                u {
                    h5 {
                        a (href:"$url/browse/$key", target: "_blank", "$summary [$key]")
                    }
                }
            } else {
				u {h5 summary}
            }
            if (wikiNote) {
        		writer.append(releaseNote);
            } else {
            	p releaseNote
            }
        }
	}
}

public static String getReleasenoteHeader() {
    StringWriter builder = new StringWriter();
    MarkupBuilder markupBuilder = new groovy.xml.MarkupBuilder(builder);
        markupBuilder.head () {
            //script (type: "text/javascript")
            if (true) {
            	// link rel: "Stylesheet", type: "text/css", media: "all", href: "http://online-help.sageerpx3.com/erp/99/wp-static-content/news/en_US/ReleaseNote_11/ADXHelp_main.css"
            	link rel: "stylesheet", type: "text/css", media: "all", href: "ADXHelp_main.css"
            } else {
                // for example...
                style {
                    h1 color: "blue";
                }
            }
        }
	return builder.toString();
}

public static String getReleasenoteBodyIssue(String key, Map fields, Map renderedFields, MultivaluedMap queryParams) {
    def builder = new StringWriter();
    def markupBuilder = new MarkupBuilder(builder);
    def writer = new StringWriter();
	def wikiNote = (queryParams.getFirst("staging") == "yes");

	if (fields) {
        // issuetype
        String issuetype = ((Map) fields.get("issuetype")).get("name");
		// summary and note
        String summary, releaseNote;
        if (issuetype == "Epic") {
            // Epic
        	summary = fields.get("customfield_10801");
            if (wikiNote) {
        		releaseNote = renderedFields.get("customfield_14806");
            } else {
        		releaseNote = fields.get("customfield_14806");
            }
        } else {
            // Bug
        	summary = fields.get("summary");
        	releaseNote = fields.get("customfield_15118");
        }
        if (releaseNote == "See comment") {
            // Comments (for POC, get the comment as the release note field)
            Map comment = renderedFields.get("comment");
            if (comment) {
                List comments = (List) comment.getAt("comments");
                if (comments) {
                    releaseNote = comments[0].getAt("body");
                    wikiNote = true;
                }
            }
        }
		// Feature (inwardIssue, member of issuelinks)
        String summaryFeature, keyFeature;
        List issuelinks = (List) fields.getAt("issuelinks");
        issuelinks.eachWithIndex {issuelink, idx ->
            Map link = (Map) issuelink;
            if (link) {
                // "inward": "is part of"
                Map type = (Map) link.get("type");
                if (type && type.get("inward") == "is part of") {
                    Map inwardIssue = (Map) link.get("inwardIssue");
                    if (inwardIssue) {
                        keyFeature = inwardIssue.get("key"); 
                        Map field = inwardIssue.get("fields");
                        if (field) {
                            summaryFeature = field.get("summary");
                        }
                    }
                }
            }
        }

/*
mkp.html{
   head{ 
      title "bijoy's groovy"
   }
   body{
      div(style:"color:red"){
         p "this is cool"
      }
   }
}
*/
/*
builder.html {     
  head {         
    title"XML encoding with Groovy"     
  }     
  body {
    h1"XML encoding with Groovy"   
    p"this format can be used as an alternative markup to XML"      

    a(href:'http://groovy.codehaus.org', "Groovy")

    p {
      mkp.yield "This is some"
      b"mixed"   
      mkp.yield " text. For more see the"
      a(href:'http://groovy.codehaus.org', "Groovy")
      mkp.yield "project"    
    }
    p "some text"    
  } 
}
*/
        def backgroundColor = "transparent";
        if (queryParams.getFirst("type") == "internal") {
        	backgroundColor = "lightyellow";
		}
		String url = getJiraSageUrl(queryParams);

        markupBuilder.div (style: "background-color:$backgroundColor", id:"$key") {
            // title ('Heading')
            if (queryParams.getFirst("onlylevel") == "yes") {
                String productArea = ((Map) fields.get("customfield_15522")).get("value");
                p "[$productArea][$issuetype][$key] $summary"
            } else {
                p {
                    if (queryParams.getFirst("type") == "internal") {
                        a (href:"$url/browse/$key", "$summary [$key]")
                    } else {
                        u summary
                    }
                }
                if (!wikiNote) {
                    p releaseNote
                }
                /* if (summaryFeature && summaryFeature != "") {p summaryFeature + " [JIRA#$keyFeature]"} */
            }
        }
		// releaseNote is directly in html format
        if (wikiNote) {
            def wikireleaseNote;
        	if (queryParams.getFirst("type") == "internal") {
                wikireleaseNote = '<div style="background-color:lightblue">' + releaseNote + '</div>';
            } else {
                wikireleaseNote = releaseNote;
            }
			writer.append(wikireleaseNote);
            // Video
            if (key == "X3-22204") {
            	writer.append('<video width="320" height="240" controls><source src="https://www.w3schools.com/tags/movie.mp4" type="video/mp4">Your browser does not support the video tag.</video>');
            }
        }
    }

    return builder.toString() + System.getProperty("line.separator") + writer.toString();
}

public static String headerDocument(String format) {
	ReadmeBuilder builder = new ReadmeBuilder();
    builder.addLine("All confidential information transmitted in this document is provided for information purposes solely and cannot be considered as complete.");
    builder.addLine("The partner, user-customer or their sub-contractor receiving this confidential information undertakes:");
    builder.addLine("- to keep it strictly confidential and neither to publish it nor to communicate it to third parties, including within the framework of legal procedures;");
    builder.addLine("- not to use it directly or indirectly for personal purposes or for purposes other than those necessary to carry out their activities;");
    builder.addLine("- to circulate it only to those employees needing to know them, after having previously informed said employees of the confidential nature of this information, the partner and user-customer guaranteeing that their employees and possible sub-contractors will comply with these confidentiality requirements;");
    builder.addLine("- not to duplicate, copy or reproduce this confidential information.");
    builder.addLine("Any breach of these provisions will compel the partner, user-customer or their sub-contractor to repair all damages, whatever their nature, undergone by Sage or a Third-Party on the basis of a claim lodged with Sage.");
    builder.addLF();
    
    switch (format) {
        case "text":
  			return builder.build();
        case "html":
            StringWriter writer = new StringWriter();
        	MarkupBuilder newBuilder = new MarkupBuilder(writer);
        	newBuilder.p(builder.build());
        	return writer.toString();
    }
}

public static String pDocument(String paragraph, String format, String style) {
	ReadmeBuilder builder = new ReadmeBuilder();
    builder.addLine(paragraph);

    switch (format) {
        case "text":
  			return builder.build();
        case "html":
    		StringWriter writer = new StringWriter();
        	MarkupBuilder newBuilder = new MarkupBuilder(writer);
            if (style) {
                switch (style) {
                    case "U":
        				newBuilder.U(paragraph);
                    	break;
                }
            } else {
        		newBuilder.p(paragraph);
            }
        	return writer.toString();
    }
}

public static String hDocument(String h, int level, String anchor, String pclass, String format) {
	switch (format) {
        case "text":
			ReadmeBuilder builder = new ReadmeBuilder();
        	String carUnderline;
            switch (level) {
				case 1: 
                	carUnderline = "=";
                	break;
				case 2:
                	carUnderline = "-";
                	break;
                default :
                	carUnderline = "-";
            }
    		builder.addLF();
    		builder.addLine(repeat(carUnderline, h.length()));
    		builder.addLine(h);
    		builder.addLine(repeat(carUnderline, h.length()));
  			return builder.build();
        case "html":
    		StringWriter writer = new StringWriter();
        	MarkupBuilder newBuilder = new MarkupBuilder(writer);
        	newBuilder.a(name: anchor, "");
            switch (level) {
				case 1:
        			newBuilder.h1(class: pclass, h);
                	break;
				case 2:
        			newBuilder.h2(class: pclass, h);
                	break;
				case 3:
        			newBuilder.h3(class: pclass, h);
                	break;
				case 4:
        			newBuilder.h4(class: pclass, h);
                	break;
				case 5:
        			newBuilder.h5(class: pclass, h);
                	break;
				case 6:
        			newBuilder.h6(class: pclass, h);
                	break;
            }
        	return writer.toString();
    }
}

public static String divTag(String value) {

	def hashmap = [Finance:['#MIS_10_Finance','Finance'], Distribution:['#MIS_10_Distribution','Distribution']];
    def writer = new StringWriter();
    def mkup = new MarkupBuilder(writer);
    mkup.html {
        div(id: "main") {
            ul {
                hashmap.collect {k, vList ->     
                    li {a href: vList[0], vList[1]}
                }
            }
        }
    }
    return writer.toString();

}

public static String h1Document(String h1, String format) {
	ReadmeBuilder builder = new ReadmeBuilder();

    switch (format) {
        case "text":
    		builder.addLF();
    		builder.addLine(repeat("*", h1.length()));
    		builder.addLine(h1);
    		builder.addLine(repeat("*", h1.length()));
  			return builder.build();
        case "html":
    		StringWriter writer = new StringWriter();
        	MarkupBuilder newBuilder = new MarkupBuilder(writer);
        	newBuilder.h1(h1);
        	return writer.toString();
    }
}

public static String h2Document(String h2, String format) {
	ReadmeBuilder builder = new ReadmeBuilder();

    switch (format) {
        case "text":
    		builder.addLF();
    		builder.addLine(repeat("=", h2.length()));
    		builder.addLine(h2);
    		builder.addLine(repeat("=", h2.length()));
  			return builder.build();
        case "html":
    		StringWriter writer = new StringWriter();
        	MarkupBuilder newBuilder = new MarkupBuilder(writer);
        	newBuilder.h2(h2);
        	return writer.toString();
    }
}

public static String h3Document(String h3, String text, String summary, String format) {
	ReadmeBuilder builder = new ReadmeBuilder();

    switch (format) {
        case "text":
    		builder.addLF();
    		builder.addLine(text);
    		builder.addLine(repeat("-", text.length()));
  			return builder.build();
        case "html":
        	StringWriter writer = new StringWriter();
			MarkupBuilder newBuilder = new MarkupBuilder(writer);
        	newBuilder.p(newBuilder.a(href: h3, text) + " " + summary);
			return writer.toString();
    }
}

public static String hrefDocument(String href, String text, String format) {
	ReadmeBuilder builder = new ReadmeBuilder();

    switch (format) {
        case "text":
    		builder.append(" ("+href+")");
  			return builder.build();
        case "html":
    		StringWriter writer = new StringWriter();
        	MarkupBuilder newBuilder = new MarkupBuilder(writer);
        	newBuilder.a(href: href, text);
    		// builder.append("<br>");
        	return writer.toString();
    }
}

public static String footerDocument(String format) {
	ReadmeBuilder builder = new ReadmeBuilder();
    String footer = "End of report";
    
    switch (format) {
        case "text":
    		builder.addLF();
    		builder.addLine(footer);
  			return builder.build();
        case "html":
    		builder.addLine("");
            StringWriter writer = new StringWriter();
        	MarkupBuilder newBuilder = new MarkupBuilder(writer);
        	newBuilder.p(builder.build());
        	return writer.toString();
    }
}

/*
*/
class ReadmeBuilder {
    private StringWriter script = new StringWriter()

    public ReadmeBuilder append(final String scriptLine) {
        script.append(scriptLine)
        return this
    }

    public ReadmeBuilder addLine(final String scriptLine) {
        script.append(scriptLine)
        script.append(newLine())
        return this
    }

    public ReadmeBuilder addLF() {
        script.append(newLine())
        return this
    }

    public ReadmeBuilder addFF() {
        script.append("\f")
        return this
    }

    private String newLine() {
        return System.getProperty("line.separator")
    }

    public String build() {
        return script.toString()
    }
}

/*
*/
class QueryString {

  private String query;

  public QueryString(String base, String api) {
    query = base + api;
  }

  public void add(String name, String value) {
    query += "&";
    encode(name, value);
  }

  private void encode(String name, String value) {
    try {
      query += URLEncoder.encode(name, "UTF-8");
      query += "=";
      query += URLEncoder.encode(value, "UTF-8");
    } catch (UnsupportedEncodingException ex) {
      throw new RuntimeException("Broken VM does not support UTF-8");
    }
  }

  public String getQuery() {
    return query;
  }

  public String toString() {
    return getQuery();
  }

}

/*
*/
public class JiraFilter {
	private String jql = null;
    private String errorMessage = null;
    private int httpResponse;

	public JiraFilter(String filterId) {
	    BodyResponse myResponse = new BodyResponse("https://jira-sage.valiantyscloud.net/rest/api/2", "/filter", "/"+filterId);
        this.httpResponse = myResponse.getHttpCode();
        if (myResponse.getHttpCode().equals(200)) {
            def jsonSlurper = new groovy.json.JsonSlurper();
            Map jsonResult = (Map) jsonSlurper.parseText(myResponse.getBody());
            this.jql = jsonResult.get("jql");
        } else {
        	this.errorMessage = myResponse.getErrorMessage();
        }
    }

    public boolean isValidFilter() {
      	return (this.httpResponse == 200);
   	}

    public String getJql() {
      	return this.jql;
   	}

    public String getErrorMessage() {
      	return this.errorMessage;
   	}
}

/*
@see https://jira-sage.valiantyscloud.net/rest/api/2/issue/<issuekey>/properties/changedfiles
*/
public class ChangedFiles {
	private ArrayList files = new ArrayList();
    private String errorMessage = null;
    private int httpResponse;

	public ChangedFiles(String issueKey) {
	    BodyResponse myResponse = new BodyResponse("https://jira-sage.valiantyscloud.net/rest/api/2", "/issue", "/"+issueKey+"/properties/changedfiles");
        this.httpResponse = myResponse.getHttpCode();
        if (myResponse.getHttpCode().equals(200)) {
            def jsonSlurper = new groovy.json.JsonSlurper();
            Map jsonResult = (Map) jsonSlurper.parseText(myResponse.getBody());
            Map valueMap = jsonResult.get("value");
            List commitsList = valueMap.get("commits");
            if (commitsList) {
                commitsList.each {
            		Map commit = (Map) it;
            		List filesList = commit.get("files");
                    if (filesList) {
                		filesList.each {
            				Map file = (Map) it;
                    		this.files.add(file.get("filename"));
                        }
                    }
                }
            }

        } else {
        	this.errorMessage = myResponse.getErrorMessage();
        }
    }

    public boolean asChangedFiles() {
      	return (this.httpResponse == 200);
   	}

    public ArrayList getFiles() {
      	return this.files;
   	}

    public String getErrorMessage() {
      	return this.errorMessage;
   	}
}

/*
*/
public class BodyResponse {
    private HttpURLConnection connection;

	public BodyResponse(String baseURL, String API, String query) {
        def bodyResponse, typeResponse;
        URL url = new java.net.URL(baseURL + API + query);

        def authString = "INDUSPRD" + ":" + "INDUSPRD";
        byte[] authEncBytes = Base64.encodeBase64(authString.getBytes());
        String authStringEnc = new String(authEncBytes);
        connection = (HttpURLConnection) url.openConnection();
        // connection.setRequestProperty("Authorization", "Basic " + authStringEnc);
    	connection.setRequestProperty("Authorization", buildBasicAuthorizationString("INDUSPRD", "INDUSPRD"));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestMethod("GET");
        connection.connect();
    }

    public int getHttpCode() {
      	return connection.getResponseCode();
   	}

    public String getBody() {
      	return connection.getInputStream().getText();
   	}

    public String getErrorMessage() {
        def jsonSlurper = new groovy.json.JsonSlurper();
        Map jsonResult = (Map) jsonSlurper.parseText(connection.getErrorStream().getText());
        List messages = (List) jsonResult.get("errorMessages");
        return messages[0];
   	}

    private String buildBasicAuthorizationString(String username, String password) {

        String credentials = username + ":" + password;
        return "Basic " + new String(new Base64().encode(credentials.getBytes()));
    }
}

public class Release {
    private String name;
    private String month;
    private String href;

    public String getName() {
      	return name;
   	}
	public String getMonth() {
      	return month;
   	}
    public String getHref() {
      	return href;
    }
}

public class ProductArea {
    private String name;
    private String href;

	public String getName() {
      	return name;
   	}
    public String getHref() {
      	return href;
    }
}

class Person {
    String name
    int age
}

/** 
* Copy a file from source to destination. 
* 
* @param source the source 
* @param destination the destination 
* @return True if succeeded , False if not 
*/ 
public static boolean copy(InputStream source, String destination) {
    boolean succeess = true;

	try { 
    	Files.copy(source, Paths.get(destination), StandardCopyOption.REPLACE_EXISTING);
	} catch (IOException ex) {
    	succeess = false;
	} 

return succeess;
}

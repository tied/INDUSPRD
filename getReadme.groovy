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
    if (!queryParams.getFirst("debug")) {queryParams.putSingle("debug", "no")};
    // Checks mandatory parameters
    try {
        assert (report.toLowerCase() == "readme" || report.toLowerCase() == "releasenote") : "'model' parameter should be Readme ou Releasenote";
        assert (typeDocument.toLowerCase() == "internal" || typeDocument.toLowerCase() == "external") : "'type' parameter should be Internal ou External";
        if (!filterId && !keys) {
        	assert (release) : "'release' parameter is mandatory";
        }
        assert (format.toLowerCase() == "text" || format.toLowerCase() == "html") : "'format' parameter should be text ou html";
        assert (component.toUpperCase() == "X3" || component.toLowerCase() == "syracuse") : "if given, 'component' parameter should be set to Syracuse";
    } catch (AssertionError e) {
        responseBuilder = Response.status(Status.NOT_FOUND).type("text/plain").entity("Something is wrong: " + e.getMessage());
        return responseBuilder.build();
    }
	queryParams.putSingle("report", report.toLowerCase())
	queryParams.putSingle("type", typeDocument.toLowerCase())
	queryParams.putSingle("format", format.toLowerCase())
	queryParams.putSingle("component", component.toUpperCase())
	queryParams.putSingle("header", header.toLowerCase())
	queryParams.putSingle("files", files.toLowerCase())

	// Base url
    def baseURL = "https://jira-sage.valiantyscloud.net/rest/api/latest";
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
        Jql += " ORDER BY issuetype DESC, 'X3 Product Area' ASC, priority";

        // fields: X3 Solution Details(15118), X3 Product Area(15522), X3 Maintenances(15112), X3 Regression(15110)
        query = "&fields=summary,customfield_15118,customfield_15522,customfield_15112,issuetype,priority,customfield_15110,fixVersions";
    } else if (report.toLowerCase() == "releasenote") {
        // Issue type
        Jql += " AND issuetype in (Epic, Bug)";
        // Status
        Jql += " AND status=Done";
        // 
        Jql += " AND (";
        Jql += "(issuetype=Epic AND 'X3 Release Note Check'='To be communicated')";	//Features
        Jql += " OR (issuetype=Bug AND 'X3 Behaviour Change'=Yes)";	// Changes
        Jql += ")";
        // Releases
        Jql += " AND fixVersion in versionMatch('^"+release+"')";
        // JIRA keys (optionnal)
        if (keys) {
            Jql += " AND issuekey in ("+keys+")";
        }
        // Order
        Jql += " ORDER BY 'X3 Product Area' ASC, issuetype ASC, 'X3 Legislation' DESC";

        // fields: Epic name(10801), X3 Release Note(14806), X3 Product Area(15522 -> h3), X3 Legislation(15413 -> h5), Feature(inwardIssue.fields.summary -> h6)
		//         summary, X3 Solution Details(15118)
        query = "&fields=issuetype,summary,customfield_10801,customfield_14806,customfield_15522,customfield_15413,customfield_15118,issuelinks,comment";
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
            	/*
                StringWriter builderHead = new StringWriter();
                MarkupBuilder markupBuilder = new groovy.xml.MarkupBuilder(builderHead);
                markupBuilder.head () {
                	title "RELEASE NOTES"
                    script type: "text/javascript"
                    link rel: "Stylesheet", type: "text/css", media: "all", href: "http://online-help.sageerpx3.com/erp/99/wp-static-content/news/en_US/ReleaseNote_11/ADXHelp_main.css"
                }
      			builder.append((String) builderHead.toString()); */
       			// builder.append(getReleasenoteHeader());
				builder.append(getReleasenoteHeader());
				//builder.append(hDocument("RELEASE NOTES", 1, "", format));
                //builder.append(hDocument(release, 2, "", format));
            	break;
        }

        // Body of document
        // Break fiels definition
        String fieldValue, breakValue;
        Map breakMap = (Map) jsonSlurper.parseText('{"breakFields":[]}');
        List breakFields = (List) breakMap.getAt("breakFields");
        int hOffset;
        switch (report.toLowerCase()) {
            case "readme":
            	// fixVersions
                if (false) {
                    breakFields.push((Map) jsonSlurper.parseText('{"field":"fixVersions","subfield":{"name":"name","breakValue":""}}'));
                }
            	// issuetype (Bug, Entry Point)
            	breakFields.push((Map) jsonSlurper.parseText('{"field":"issuetype","subfield":{"name":"name","breakValue":""}}'));
            	// For Syracuse, break only
            	if (component.toLowerCase() != "syracuse") {
            		// X3 Product Area
					breakFields.push((Map) jsonSlurper.parseText('{"field":"customfield_15522","subfield":{"name":"value","breakValue":""}}'));
                }
            	hOffset = 0;
            	break;
            case "releasenote":
            	// X3 Product Area
            	breakFields.push((Map) jsonSlurper.parseText('{"field":"customfield_15522","subfield":{"name":"value","breakValue":""},"level":"3","class":"g1"}'));
            	// issuetype
				breakFields.push((Map) jsonSlurper.parseText('{"field":"issuetype","subfield":{"name":"name","breakValue":""},"level":"3","class":"changes"}'));
            	// X3 Legislation
				breakFields.push((Map) jsonSlurper.parseText('{"field":"customfield_15413","subfield":{"name":"value","breakValue":""},"level":"5","class":""}'));
            	// Feature
				// breakFields.push((Map) jsonSlurper.parseText('{"field":"issuelinks","subfield":{"name":"summary","breakValue":""}}'));
            	hOffset = 2;
            	break;
        }

        // https://docs.oracle.com/javase/8/docs/api/java/util/List.html
        List issues = (List) jsonResult.get("issues");
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
                            if (name.indexOf(release) >= 0) {
                           		fieldValue = name;
                                return;
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
						Integer level = ((String) breakField.getAt("level")).toInteger();
						String hclass = breakField.getAt("class");
                        // builder.append(hDocument(fieldValue, idx+1+hOffset, anchor, hclass, format));
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
        			builder.append(getReleasenoteBody(key, fields, renderedFields, queryParams));
                	break;
            }
		};

        // Footer of document
        builder.append(footerDocument(format));

		// Store the builder text to the bodyResponse
        switch (format) {
            case "html":
        		typeResponse = "text/html";
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
		responseBuilder = Response.status(connection.getHttpCode()).type("text/plain").entity("Oops! Error "+connection.getHttpCode());
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
		if (queryParams.getFirst("typeDocument") == "internal") { //if (typeDocument.toLowerCase() == "internal") {
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

public static String getReleasenoteHeader() {
    StringWriter builder = new StringWriter();
    MarkupBuilder markupBuilder = new groovy.xml.MarkupBuilder(builder);
    markupBuilder.head () {
        title "RELEASE NOTES"
        //script (type: "text/javascript")
        link rel: "Stylesheet", type: "text/css", media: "all", href: "http://online-help.sageerpx3.com/erp/99/wp-static-content/news/en_US/ReleaseNote_11/ADXHelp_main.css"
    }
	return builder.toString();
}

public static String getReleasenoteBody(String key, Map fields, Map renderedFields, MultivaluedMap queryParams) {
    StringWriter builder = new StringWriter();
    MarkupBuilder markupBuilder = new groovy.xml.MarkupBuilder(builder);

    if (fields) {
        // issuetype
        String issuetype = ((Map) fields.get("issuetype")).get("name");
		// summary and note
        String summary, releaseNote;
        if (issuetype == "Epic") {
            // Epic
        	summary = fields.get("customfield_10801");
        	releaseNote = fields.get("customfield_14806");
        } else {
            // Bug
        	summary = fields.get("summary");
        	releaseNote = fields.get("customfield_15118");
        }
		// Comments (for POC, get the comment as the release note field)
        Map comment = renderedFields.get("comment");
        String newReleaseNote;
        if (comment) {
            List comments = (List) comment.getAt("comments");
            if (comments) {
                releaseNote = comments[0].getAt("body");
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

        def backgroundColor = "transparent";
        if (queryParams.getFirst("type") == "internal") {
        	backgroundColor = "lightyellow";
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
		markupBuilder.div (style: "background-color:$backgroundColor", id:"$key") {
        	// title ('Heading')
            if (queryParams.getFirst("debug") == "yes") {
        		String productArea = ((Map) fields.get("customfield_15522")).get("value");
                p "[$productArea][$issuetype][$key] $summary"
            } else {
                p {
                    if (queryParams.getFirst("type") == "internal") {
                    	a (href:"https://jira-sage.valiantyscloud.net/browse/$key", "$summary [$key]")
                    } else {
						u summary
                    }
                }
                p releaseNote
                if (false && newReleaseNote) {
                    p newReleaseNote
                }
				/*
                if (summaryFeature && summaryFeature != "") {
                    p summaryFeature + " [JIRA#$keyFeature]"
                }*/
            }
		}
    }

    return builder.toString();
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

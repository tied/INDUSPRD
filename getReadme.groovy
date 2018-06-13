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
import groovy.xml.MarkupBuilder;
import groovyx.net.http.RESTClient;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.MediaType;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.util.regex.Pattern;

import javax.swing.text.html.HTMLDocument;
import javax.swing.JEditorPane;

import static java.util.stream.Collectors.joining;

@BaseScript CustomEndpointDelegate delegate

getReadme(httpMethod: "GET", groups: ["X3 Users"]) { MultivaluedMap queryParams ->
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
    String modelDocument = queryParams.getFirst("model");
    if (!modelDocument) {modelDocument = "readme"};
    // type: External (default), Internal
    String typeDocument = queryParams.getFirst("type");
    if (!typeDocument) {typeDocument = "External"};
	// release
    String release = queryParams.getFirst("release");
    // format: text (default), html
    String format = queryParams.getFirst("format");
    if (!format) {format = "text"};
    // keys: JIRA keys for testing 'X3-111,X3-222,...'
    String keys = queryParams.getFirst("keys");
    // filterid: filter id or if null, keep the jql programmed
    String filterId = queryParams.getFirst("filterid");
    try {
        assert (typeDocument.toLowerCase() == "internal" || typeDocument.toLowerCase() == "external") : "'type' parameter should be Internal ou External";
        assert (release) : "'release' parameter is mandatory";
        assert (format.toLowerCase() == "text" || format.toLowerCase() == "html") : "'format' parameter should be text ou html";
    } catch (AssertionError e) {
        responseBuilder = Response.status(Status.NOT_FOUND).type("text/plain").entity("Something is wrong: " + e.getMessage());
        return responseBuilder.build();
    }

	// Base url
    def baseURL = "https://jira-sage.valiantyscloud.net/rest/api/2";
    // Search API syntax
    def API = "/search";
    // JQL query
    String Jql, query;
    if (modelDocument.toLowerCase() == "readme") {
        Jql = "project=X3";
        // Issue type
        Jql += " AND issuetype in (Bug,'Entry Point')";
        // Status
        Jql += " AND status=Done";
        // Resolution
        Jql += " AND resolution in (Done,Fixed)";
        // 
        if (typeDocument.toLowerCase() == "external") {
            Jql += " AND 'X3 ReadMe Check'='To be communicated'";
        }
        // Releases
        Jql += " AND fixVersion in versionMatch('"+release+"')";
        // JIRA keys (optionnal)
        if (keys) {
            Jql += " AND issuekey in ("+keys+")";
        }
        // Order
        Jql += " ORDER BY 'X3 Product Area' ASC";

        // fields: X3 Solution Details(15118), X3 Product Area(15522), X3 Maintenances(15112)
        query = "&fields=summary,customfield_15118,customfield_15522,customfield_15112,issuetype,priority";
    } else if (modelDocument.toLowerCase() == "releasenote") {
        Jql = "project=X3";
        // Issue type
        Jql += " AND issuetype in (Epic)";
        // Status
        Jql += " AND status=Done";
        // 
        if (typeDocument.toLowerCase() == "external") {
            Jql += " AND 'X3 Release Note Check'='To be communicated'";
        }
        // Releases
        Jql += " AND fixVersion in versionMatch('"+release+"')";
        // JIRA keys (optionnal)
        if (keys) {
            Jql += " AND issuekey in ("+keys+")";
        }
        // Order
        Jql += " ORDER BY 'X3 Product Area' ASC";

        // fields: summary, X3 Release Note(14806), X3 Product Area(15522)
        query = "&fields=summary,customfield_14806,customfield_15522";
    }
    if (filterId) {
        // Get the query from the filter given by parameter
    	Jql = getFilter(filterId);
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
    
	URL url = new java.net.URL(baseURL + API + "?" + "jql="+removedSpaces(Jql) + query);

	def authString = "INDUSPRD" + ":" + "INDUSPRD";
	byte[] authEncBytes = Base64.encodeBase64(authString.getBytes());
	String authStringEnc = new String(authEncBytes);

    // https://docs.oracle.com/javase/8/docs/api/java/net/HttpURLConnection.html
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
	connection.setRequestProperty("Authorization", "Basic " + authStringEnc);
	connection.setRequestProperty("Content-Type", "application/json");
   	connection.setRequestMethod("GET");
    connection.connect();
    // https://docs.oracle.com/javaee/7/api/javax/ws/rs/core/Response.html
    if (connection.getResponseCode().equals(200)) {
        bodyResponse = connection.getInputStream().getText();
        // Parse the response JSON
        def jsonSlurper = new groovy.json.JsonSlurper();
        Map jsonResult = (Map) jsonSlurper.parseText(bodyResponse);

        // total (issues)
        Integer total = (Integer) jsonResult.get("total");

        // Write the text of readme document
        StringWriter builder = new StringWriter();
		// for html
		MarkupBuilder markupBuilder = new MarkupBuilder(builder);
        MarkupBuilder myBuilder = new MarkupBuilder(builder);

        // Header of document
        // @see https://docs.oracle.com/javase/7/docs/api/javax/swing/JEditorPane.html
        builder.append(headerDocument(format));

        builder.append(pDocument("Content: Bug Fixes and Entry Points", format));
        builder.append(pDocument("Product: Sage X3 & Platform", format));

        builder.append(pDocument("Release: " + release, format));
        
        // Body of document
        // Break fiels definition
        String h1, h2, h1Rupture, h2Rupture, breakValue;
        def mapFields, hFields, breakValues;
        switch (modelDocument.toLowerCase()) {
            case "readme":
            	// issuetype
				hFields = ["issuetype", "customfield_15522"] as List;
            	mapFields = ["issuetype":"name", "customfield_15522":"value"] as Map;
            	breakValues = ["issuetype":"", "customfield_15522":""] as Map;
            	break;
            case "releasenote":
            	// X3 Product Area
				hFields = ["customfield_15522"] as List;
            	mapFields = ["customfield_15522":"value"] as Map;
            	breakValues = ["customfield_15522":""] as Map;
            	break;
        }
        // https://docs.oracle.com/javase/8/docs/api/java/util/List.html
        List issues = (List) jsonResult.get("issues");
        issues.each {
            Map issue = (Map) it;
    		String key = issue.get("key");
    		Map fields = issue.get("fields");

            // Retreive the rupture fields
            hFields.each {
            	breakValue = ((Map) fields.get(it)).get(mapFields[it]);
                if (breakValues[it] == null || breakValues[it] != breakValue) {
                    // Case of issuetype value
                    if (it == "issuetype") {
                        if (breakValue == "Bug") {
                            breakValue = "BugFixes";
                        } else if (breakValue == "Entry Point") {
                            breakValue = "Entry Points";
                        }
                    }
                    // Write the rupture fields
                    builder.append(h1Document(breakValue, format));
                    breakValues[it] = breakValue;
                }
            }
            // h3: Issue
            if (typeDocument.toLowerCase() == "internal") {
            	builder.append(h3Document("https://jira-sage.valiantyscloud.net/browse/", key, "summary", format));
            }

            // Write the body issue
            //builder.append(getIssueReadme(it, typeDocument, format));
        	builder.append(getIssueReadme(key, fields, typeDocument, format));
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

		/*
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
		responseBuilder = Response.status(connection.getResponseCode()).type("text/plain").entity("Error"+connection.getResponseCode());
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

public static String getBullet(priority) {
    def priorities = [Minor:"*", Major:"-", Critical:"^", Blocker:"x"];
    return priorities[priority];
}

public static String getIssueReadme(String key, Map fields, String typeDocument, String format) {
    StringBuilder builder = new StringBuilder();
    def markupBuilder, writer;
    if (format == "html") {
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

        // Write issue
        if (typeDocument.toLowerCase() == "internal") {
            // format for Internal readme
        	builder.append(pDocument(summary, format));
        	builder.append(pDocument("Details: " + solution, format));
            if (maintenancesVersion && !maintenancesVersion.isEmpty()) {
                builder.append("Maintenances: ");
                def pattern = /\[[0-9]*\|/
                maintenancesVersion.each {
                    def maintenancesNumber = it =~ pattern;
                    maintenancesNumber.each {
                        // builder.append(it.toString()+",")
        				builder.append(pDocument(it.toString()+",", format));
                    };
                };
            }
        } else {
            // format for External readme
    		def priorities = [Minor:"-", Major:"*", Critical:"^", Blocker:"!"];
        	builder.append(pDocument(priorities[priorityValue] + " $summary [JIRA#$key]", format));
            builder.append(pDocument(solution, format));
        }
    }
    if (format == "html") {
  		return builder.toString();
    } else {
  		return builder.toString();
    }
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

public static String pDocument(String paragraph, String format) {
	ReadmeBuilder builder = new ReadmeBuilder();
    builder.addLine(paragraph);

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
    		builder.addLine(footer);
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

public static String getFilter(String filterId) {
    
    String bodyResponse = getBodyResponse("https://jira-sage.valiantyscloud.net/rest/api/2", "/filter", "/"+filterId);

    def jsonSlurper = new groovy.json.JsonSlurper();
    Map jsonResult = (Map) jsonSlurper.parseText(bodyResponse);

    return jsonResult.get("jql");
}

public static String getBodyResponse(String baseURL, String API, String query) {
    def bodyResponse, typeResponse;
	URL url = new java.net.URL(baseURL + API + query);

	def authString = "INDUSPRD" + ":" + "INDUSPRD";
	byte[] authEncBytes = Base64.encodeBase64(authString.getBytes());
	String authStringEnc = new String(authEncBytes);

    // https://docs.oracle.com/javase/8/docs/api/java/net/HttpURLConnection.html
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
	connection.setRequestProperty("Authorization", "Basic " + authStringEnc);
	connection.setRequestProperty("Content-Type", "application/json");
   	connection.setRequestMethod("GET");
    connection.connect();
    // https://docs.oracle.com/javaee/7/api/javax/ws/rs/core/Response.html
    if (connection.getResponseCode().equals(200)) {
        bodyResponse = connection.getInputStream().getText();
	}
    return bodyResponse;
}

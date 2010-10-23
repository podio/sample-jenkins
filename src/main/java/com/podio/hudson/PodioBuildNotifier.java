package com.podio.hudson;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.podio.BaseAPI;
import com.podio.oauth.OAuthClientCredentials;
import com.podio.oauth.OAuthUsernameCredentials;
import com.podio.org.OrgAPI;
import com.sun.jersey.api.client.UniformInterfaceException;

public class PodioBuildNotifier extends Notifier {

	protected static final Logger LOGGER = Logger
			.getLogger(PodioBuildNotifier.class.getName());

	private final String username;
	private final String password;
	private final int appId;

	private static final OAuthClientCredentials CLIENT_CREDENTIALS = new OAuthClientCredentials(
			"dev@hoisthq.com", "CmACRWF1WBOTDfOa20A");

	@DataBoundConstructor
	public PodioBuildNotifier(String username, String password, int appId) {
		this.username = username;
		this.password = password;
		this.appId = appId;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public int getAppId() {
		return appId;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	@Extension
	public static final class DescriptorImpl extends
			BuildStepDescriptor<Publisher> {

		private String hostname = "localhost";

		private int port = 9090;

		private boolean ssl = false;

		public DescriptorImpl() {
			super(PodioBuildNotifier.class);
			load();
		}

		@Override
		public String getDisplayName() {
			return "Podio Build Poster";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData)
				throws FormException {
			req.bindParameters(this, "podio.");
			save();
			return super.configure(req, formData);
		}

		private BaseAPI getBaseAPI(String username, String password) {
			return new BaseAPI(hostname, port, ssl, CLIENT_CREDENTIALS,
					new OAuthUsernameCredentials(username, password));
		}

		public FormValidation doValidate(
				@QueryParameter("username") final String username,
				@QueryParameter("password") final String password)
				throws IOException, ServletException {
			BaseAPI baseAPI = getBaseAPI(username, password);
			OrgAPI orgAPI = new OrgAPI(baseAPI);
			try {
				orgAPI.getOrganizations();
				return FormValidation.ok("Username and password validated");
			} catch (UniformInterfaceException e) {
				e.printStackTrace();
				return FormValidation.error("Invalid username or password");
			}
		}

		@Override
		public Publisher newInstance(StaplerRequest req, JSONObject formData)
				throws FormException {
			return super.newInstance(req, formData);
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		public String getHostname() {
			return hostname;
		}

		public void setHostname(String hostname) {
			this.hostname = hostname;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public boolean isSsl() {
			return ssl;
		}

		public void setSsl(boolean ssl) {
			this.ssl = ssl;
		}
	}
}

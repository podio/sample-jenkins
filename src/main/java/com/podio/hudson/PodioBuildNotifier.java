package com.podio.hudson;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
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
import com.podio.root.RootAPI;
import com.podio.root.SystemStatus;
import com.podio.space.SpaceAPI;
import com.podio.space.SpaceWithOrganization;
import com.sun.jersey.api.client.UniformInterfaceException;

public class PodioBuildNotifier extends Notifier {

	protected static final Logger LOGGER = Logger
			.getLogger(PodioBuildNotifier.class.getName());

	private final String username;
	private final String password;
	private final String clientId;
	private final String clientSecret;
	private final String spaceURL;

	@DataBoundConstructor
	public PodioBuildNotifier(String username, String password,
			String clientId, String clientSecret, String spaceURL) {
		this.username = username;
		this.password = password;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.spaceURL = spaceURL;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public String getClientId() {
		return clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public String getSpaceURL() {
		return spaceURL;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		System.out.println(build.getResult().toString());

		return true;
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
			req.bindParameters(this);
			save();
			return super.configure(req, formData);
		}

		public FormValidation doValidateAuth(
				@QueryParameter("username") final String username,
				@QueryParameter("password") final String password,
				@QueryParameter("clientId") final String clientId,
				@QueryParameter("clientSecret") final String clientSecret,
				@QueryParameter("spaceURL") final String spaceURL)
				throws IOException, ServletException {
			BaseAPI baseAPI = new BaseAPI(hostname, port, ssl, false,
					new OAuthClientCredentials(clientId, clientSecret),
					new OAuthUsernameCredentials(username, password));

			try {
				SpaceWithOrganization space = new SpaceAPI(baseAPI)
						.getByURL(spaceURL);
				return FormValidation.ok("Connection ok, using space "
						+ space.getName() + " in organization "
						+ space.getOrganization().getName());
			} catch (UniformInterfaceException e) {
				if (e.getResponse().getStatus() == 404) {
					return FormValidation.error("No space found with the URL "
							+ spaceURL);
				} else {
					return FormValidation.error("Invalid username or password");
				}
			} catch (Exception e) {
				e.printStackTrace();
				return FormValidation.error("Invalid username or password");
			}
		}

		public FormValidation doValidateAPI(
				@QueryParameter("hostname") final String hostname,
				@QueryParameter("port") final String port,
				@QueryParameter("ssl") final boolean ssl) throws IOException,
				ServletException {
			int portInt;
			try {
				portInt = Integer.parseInt(port);
			} catch (NumberFormatException e) {
				return FormValidation.error("Port must be an integer");
			}

			BaseAPI baseAPI = new BaseAPI(hostname, portInt, ssl, false, null,
					null);

			try {
				SystemStatus status = new RootAPI(baseAPI).getStatus();
				return FormValidation
						.ok("Connection validated, running API version "
								+ status.getVersion());
			} catch (Exception e) {
				e.printStackTrace();
				return FormValidation.error("Invalid hostname, port or ssl");
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

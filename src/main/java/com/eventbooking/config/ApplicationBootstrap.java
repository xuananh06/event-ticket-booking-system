package com.eventbooking.config;

import com.eventbooking.controller.HomeServlet;
import com.eventbooking.controller.HealthServlet;
import com.eventbooking.controller.LoginServlet;
import com.eventbooking.controller.LogoutServlet;
import com.eventbooking.controller.RegisterServlet;
import com.eventbooking.controller.RoleHomeServlet;
import com.eventbooking.model.Role;
import com.eventbooking.dao.UserDao;
import com.eventbooking.filter.AuthenticationFilter;
import com.eventbooking.filter.AuthorizationFilter;
import com.eventbooking.filter.CsrfFilter;
import com.eventbooking.filter.RequestSecurityFilter;
import com.eventbooking.security.CsrfTokens;
import com.eventbooking.security.PasswordHasher;
import com.eventbooking.service.AuthService;
import com.eventbooking.service.DatabaseProbe;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import java.util.Optional;
import java.util.EnumSet;
import javax.sql.DataSource;

public final class ApplicationBootstrap implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        Optional<DataSource> source = DatabaseSettings.fromEnvironment(System.getenv())
                .map(DataSourceFactory::create);
        DatabaseProbe probe = new DatabaseProbe(source);
        DatabaseProbe.Status status = probe.check();
        context.log("Database startup check: " + status.name());
        WebSettings web = WebSettings.fromEnvironment(System.getenv());
        context.getSessionCookieConfig().setHttpOnly(true);
        context.getSessionCookieConfig().setSecure(web.secureCookies());
        context.getSessionCookieConfig().setAttribute("SameSite", "Lax");
        AuthService auth = new AuthService(new UserDao(source), new PasswordHasher());
        CsrfTokens tokens = new CsrfTokens(web.sessionTimeoutSeconds());
        // Explicit REQUEST order; annotations are disabled by metadata-complete web.xml.
        var dispatchers = EnumSet.of(DispatcherType.REQUEST);
        context.addFilter("requestSecurity", new RequestSecurityFilter()).addMappingForUrlPatterns(dispatchers, true, "/*");
        context.addFilter("csrf", new CsrfFilter(tokens)).addMappingForUrlPatterns(dispatchers, true, "/*");
        context.addFilter("authentication", new AuthenticationFilter(auth)).addMappingForUrlPatterns(dispatchers, true, "/*");
        context.addFilter("authorization", new AuthorizationFilter()).addMappingForUrlPatterns(dispatchers, true, "/*");
        // Manual constructor injection; no service locator or shared JDBC Connection.
        ServletRegistration.Dynamic home = context.addServlet("home", new HomeServlet());
        home.setLoadOnStartup(1);
        home.addMapping("");
        context.addServlet("health", new HealthServlet(status)).addMapping("/health");
        context.addServlet("register", new RegisterServlet(auth)).addMapping("/register");
        context.addServlet("login", new LoginServlet(auth, tokens)).addMapping("/login");
        context.addServlet("logout", new LogoutServlet()).addMapping("/logout");
        for (Role role : Role.values()) {
            context.addServlet(role.name().toLowerCase(java.util.Locale.ROOT) + "Home", new RoleHomeServlet(role)).addMapping(role.getHomePath());
        }
    }
}

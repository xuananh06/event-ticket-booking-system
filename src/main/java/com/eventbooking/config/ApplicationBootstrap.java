package com.eventbooking.config;

import com.eventbooking.controller.HomeServlet;
import com.eventbooking.service.DatabaseProbe;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import java.util.Optional;
import javax.sql.DataSource;

public final class ApplicationBootstrap implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        Optional<DataSource> source = DatabaseSettings.fromEnvironment(System.getenv())
                .map(DataSourceFactory::create);
        DatabaseProbe probe = new DatabaseProbe(source);
        DatabaseProbe.Status status = probe.check();
        context.log("P1 database startup check: " + status.name());
        // Manual constructor injection; no service locator or shared JDBC Connection.
        ServletRegistration.Dynamic home = context.addServlet("home", new HomeServlet(status));
        home.setLoadOnStartup(1);
        home.addMapping("");
    }
}

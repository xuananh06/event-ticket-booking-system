<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
    <head>
        <%@ include file="common/head.jspf" %>
    </head>
    <body>
        <%@ include file="common/navigation.jspf" %>
        <main id="main-content" class="container page-content">
            <section class="auth-card mx-auto">
                <p class="eyebrow">LET'S GET YOU BACK</p>
                <h1 class="h2 fw-bold">We couldn't complete this request.</h1>
                <p role="alert" class="text-secondary my-4"><c:out value="${empty errorMessage ? 'This page is unavailable or the request is not allowed. Please return home and try again.' : errorMessage}"/></p>
                <a class="btn btn-primary" href="<c:out value='${publicHomeUrl}'/>">Return home</a>
            </section>
        </main>
        <%@ include file="common/footer.jspf" %>
    </body>
</html>

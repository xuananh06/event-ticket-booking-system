<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
    <head>
        <%@ include file="../common/head.jspf" %>
    </head>
    <body>
        <%@ include file="../common/navigation.jspf" %>
        <main id="main-content" class="container page-content">
            <section class="account-card" data-role="<c:out value='${currentUser.role}'/>">
                <p class="eyebrow">YOUR CAMPUS EVENTS ACCOUNT</p>
                <h1 class="h2 fw-bold">Welcome, <c:out value="${currentUser.fullName}"/>.</h1>
                <p class="text-secondary">You are signed in. This is your account's welcome page.</p>
                <dl class="row account-details mt-4 mb-0">
                    <dt class="col-sm-3">Email address</dt>
                    <dd class="col-sm-9"><c:out value="${currentUser.email}"/></dd>
                    <dt class="col-sm-3">Account role</dt>
                    <dd class="col-sm-9"><span class="role-badge"><c:out value="${currentUser.role.displayName}"/></span></dd>
                </dl>
            </section>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>

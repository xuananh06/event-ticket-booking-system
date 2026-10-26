<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
<head><%@ include file="common/head.jspf" %></head>
<body>
<%@ include file="common/navigation.jspf" %>
<main id="main-content" class="container page-content">
    <section class="row align-items-center g-5 hero">
        <div class="col-lg-7">
            <p class="eyebrow">YOUR UNIVERSITY COMMUNITY</p>
            <h1 class="display-4 fw-bold"><c:out value="${welcomeMessage}"/></h1>
            <p class="lead text-secondary mt-4">Start with a personal account. Stay connected to your campus community in one welcoming space.</p>
            <div class="d-flex flex-wrap gap-3 mt-4">
                <a class="btn btn-primary btn-lg" href="<c:out value='${browseUrl}'/>">Browse upcoming events</a>
                <c:choose>
                    <c:when test="${not empty currentUser}"><a class="btn btn-primary btn-lg" href="<c:out value='${accountHomeUrl}'/>">Go to your account</a></c:when>
                    <c:otherwise><a class="btn btn-primary btn-lg" href="<c:out value='${registerUrl}'/>">Join the community</a><a class="btn btn-outline-dark btn-lg" href="<c:out value='${loginUrl}'/>">Sign in</a></c:otherwise>
                </c:choose>
            </div>
        </div>
        <div class="col-lg-5">
            <div class="feature-panel p-4 p-md-5">
                <span class="section-number">WELCOME TO CAMPUS</span>
                <h2 class="h3 my-4">A simple start.<br>A space of your own.</h2>
                <ul id="capabilities" class="capability-list">
                    <c:forEach var="capability" items="${capabilities}"><li><c:out value="${capability}"/></li></c:forEach>
                </ul>
            </div>
        </div>
    </section>
</main>
<%@ include file="common/footer.jspf" %>
</body>
</html>

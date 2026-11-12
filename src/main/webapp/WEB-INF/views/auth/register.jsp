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
            <section class="auth-card mx-auto">
                <h1 class="h2 fw-bold">Create your account</h1>
                <p class="text-secondary mb-4">Create an account to book seats and manage your reservations.</p>
                <%@ include file="../common/messages.jspf" %>
                <form method="post" action="<c:out value='${registerUrl}'/>">
                    <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                    <div class="mb-3">
                        <label class="form-label" for="fullName">Full name</label>
                        <input
                            class="form-control"
                            id="fullName" aria-invalid="${not empty errors.fullName}"
                            name="fullName"
                            autocomplete="name"
                            maxlength="150"
                            required
                            value="<c:out value='${fullName}'/>"
                            aria-describedby="fullName-error">
                        <div id="fullName-error" class="field-error"><c:out value="${errors.fullName}"/></div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label" for="username">Username</label>
                        <input
                            class="form-control"
                            id="username" aria-invalid="${not empty errors.username}"
                            name="username"
                            type="text"
                            autocomplete="username"
                            maxlength="64"
                            required
                            value="<c:out value='${username}'/>"
                            aria-describedby="username-help username-error">
                        <div id="username-help" class="form-text">Use 3-30 ASCII letters, digits, periods, underscores or hyphens. Outer whitespace is removed; usernames are stored in lowercase. Do not use @.</div>
                        <div id="username-error" class="field-error"><c:out value="${errors.username}"/></div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label" for="email">Email address</label>
                        <input
                            class="form-control"
                            id="email" aria-invalid="${not empty errors.email}"
                            name="email"
                            type="email"
                            autocomplete="email"
                            maxlength="254"
                            required
                            value="<c:out value='${email}'/>"
                            aria-describedby="email-error">
                        <div id="email-error" class="field-error"><c:out value="${errors.email}"/></div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label" for="password">Password</label>
                        <input
                            class="form-control"
                            id="password" aria-invalid="${not empty errors.password}"
                            name="password"
                            type="password"
                            autocomplete="new-password"
                            maxlength="72"
                            required
                            aria-describedby="password-help password-error">
                        <div id="password-help" class="form-text">Use 8-72 UTF-8 bytes. Some characters use more than one byte.</div>
                        <div id="password-error" class="field-error"><c:out value="${errors.password}"/></div>
                    </div>
                    <div class="mb-4">
                        <label class="form-label" for="confirmation">Confirm password</label>
                        <input
                            class="form-control"
                            id="confirmation" aria-invalid="${not empty errors.confirmation}"
                            name="confirmation"
                            type="password"
                            autocomplete="new-password"
                            maxlength="72"
                            required
                            aria-describedby="confirmation-error">
                        <div id="confirmation-error" class="field-error"><c:out value="${errors.confirmation}"/></div>
                    </div>
                    <button type="submit" class="btn btn-primary w-100">Create account</button>
                </form>
                <p class="text-center text-secondary mt-4 mb-0">Already a member? <a href="<c:out value='${loginUrl}'/>">Sign in</a></p>
            </section>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>

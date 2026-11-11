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
            <c:url var="usersUrl" value="/admin/users"/>
            <div class="d-flex flex-wrap justify-content-between gap-3 mb-4">
                <h1 class="h2">Accounts</h1>
                <a class="btn btn-outline-dark" href="<c:out value='${usersUrl}'/>">New account</a>
            </div>
            <%@ include file="../common/messages.jspf" %>
            <section class="account-card mt-0">
                <h2 class="h4"><c:out value="${empty form.id ? 'Create account' : 'Edit account'}"/></h2>
                <form method="post" action="<c:out value='${usersUrl}'/>">
                    <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                    <input type="hidden" name="action" value="save">
                    <input type="hidden" name="id" value="<c:out value='${form.id}'/>">
                    <label class="form-label" for="fullName">Full name</label>
                    <input
                        class="form-control mb-3"
                        id="fullName" aria-describedby="fullName-error" aria-invalid="${not empty errors.fullName}"
                        name="fullName"
                        maxlength="150"
                        required
                        value="<c:out value='${form.fullName}'/>">
                    <div id="fullName-error" class="field-error"><c:out value="${errors.fullName}"/></div>
                    <label class="form-label" for="email">Email address</label>
                    <input
                        class="form-control mb-3"
                        id="email" aria-describedby="email-error" aria-invalid="${not empty errors.email}"
                        name="email"
                        type="email"
                        maxlength="254"
                        required
                        value="<c:out value='${form.email}'/>">
                    <div id="email-error" class="field-error"><c:out value="${errors.email}"/></div>
                    <label class="form-label" for="role">Role</label>
                    <select class="form-select mb-3" id="role" aria-describedby="role-error" aria-invalid="${not empty errors.role}" name="role">
                        <c:forEach var="role" items="${roles}">
                            <option value="${role}" ${form.role == role.toString() ? 'selected' : ''}><c:out value="${role.displayName}"/></option>
                        </c:forEach>
                    </select>
                    <div id="role-error" class="field-error"><c:out value="${errors.role}"/></div>
                    <label class="form-label" for="active">State</label>
                    <select class="form-select mb-3" id="active" aria-describedby="active-error" aria-invalid="${not empty errors.active}" name="active">
                        <option value="true" ${form.active != 'false' ? 'selected' : ''}>Active</option>
                        <option value="false" ${form.active == 'false' ? 'selected' : ''}>Inactive</option>
                    </select>
                    <div id="active-error" class="field-error"><c:out value="${errors.active}"/></div>
                    <c:if test="${empty form.id}">
                        <label class="form-label" for="password">Initial password</label>
                        <input
                            class="form-control mb-3"
                            id="password" aria-describedby="password-error" aria-invalid="${not empty errors.password}"
                            name="password"
                            type="password"
                            autocomplete="new-password"
                            maxlength="72"
                            required>
                        <div id="password-error" class="field-error"><c:out value="${errors.password}"/></div>
                        <label class="form-label" for="confirmation">Confirm password</label>
                        <input
                            class="form-control mb-3"
                            id="confirmation" aria-describedby="confirmation-error" aria-invalid="${not empty errors.confirmation}"
                            name="confirmation"
                            type="password"
                            autocomplete="new-password"
                            maxlength="72"
                            required>
                        <div id="confirmation-error" class="field-error"><c:out value="${errors.confirmation}"/></div>
                        <p class="form-text">Use 8-72 UTF-8 bytes. Passwords are not trimmed.</p>
                    </c:if>
                    <button class="btn btn-primary" type="submit">Save account</button>
                </form>
            </section>
            <form method="get" action="<c:out value='${usersUrl}'/>" class="d-flex flex-wrap gap-3 mb-3">
                <label>Search name or email<input
                    class="form-control"
                    name="q"
                    maxlength="100"
                    value="<c:out value='${param.q}'/>"></label>
                <label>State<select class="form-select" name="filterActive">
                    <option value="">All</option>
                    <option value="true" ${param.filterActive == 'true' ? 'selected' : ''}>Active</option>
                    <option value="false" ${param.filterActive == 'false' ? 'selected' : ''}>Inactive</option>
                </select></label>
                <button class="btn btn-outline-dark align-self-end" type="submit">Search</button>
            </form>
            <p><c:out value="${result.total}"/> accounts</p>
            <p id="accounts-scroll-help" class="table-scroll-hint">Scroll the table horizontally when columns do not fit.</p>
            <div class="table-responsive table-scroll" role="region" tabindex="0" aria-labelledby="accounts-caption" aria-describedby="accounts-scroll-help">
                <table class="table align-middle table-wide">
                    <caption id="accounts-caption" class="caption-top">Accounts matching the current filters</caption>
                    <thead>
                        <tr>
                            <th scope="col">ID</th>
                            <th scope="col">Account</th>
                            <th scope="col">Role</th>
                            <th scope="col">State</th>
                            <th scope="col">Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <c:forEach var="user" items="${result.items}">
                            <tr>
                                <td class="record-id"><c:out value="${user.id}"/></td>
                                <td><c:out value="${user.fullName}"/><br><c:out value="${user.email}"/></td>
                                <td class="state-value"><c:out value="${user.role.displayName}"/></td>
                                <td class="state-value"><c:out value="${user.active ? 'Active' : 'Inactive'}"/></td>
                                <td>
                                    <div class="d-flex flex-wrap gap-2">
                                        <c:url var="editUrl" value="/admin/users">
                                            <c:param name="id" value="${user.id}"/>
                                        </c:url>
                                        <a class="btn btn-sm btn-outline-dark" href="<c:out value='${editUrl}'/>">Edit</a>
                                        <form method="post" action="<c:out value='${usersUrl}'/>">
                                            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                                            <input type="hidden" name="action" value="status">
                                            <input type="hidden" name="id" value="${user.id}">
                                            <input type="hidden" name="active" value="${not user.active}">
                                            <button class="btn btn-sm btn-outline-dark" type="submit"><c:out value="${user.active ? 'Deactivate' : 'Activate'}"/></button>
                                        </form>
                                        <form method="post" action="<c:out value='${usersUrl}'/>">
                                            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                                            <input type="hidden" name="action" value="delete">
                                            <input type="hidden" name="id" value="${user.id}">
                                            <button class="btn btn-sm btn-outline-danger" type="submit">Delete unreferenced account</button>
                                        </form>
                                    </div>
                                </td>
                            </tr>
                        </c:forEach>
                    </tbody>
                </table>
            </div>
            <c:if test="${empty result.items}">
                <p>No matching accounts.</p>
            </c:if>
            <%@ include file="../common/paging.jspf" %>
        </main>
        <%@ include file="../common/footer.jspf" %>
    </body>
</html>

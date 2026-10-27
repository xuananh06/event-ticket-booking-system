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
                        id="fullName"
                        name="fullName"
                        maxlength="150"
                        required
                        value="<c:out value='${form.fullName}'/>">
                    <label class="form-label" for="email">Email address</label>
                    <input
                        class="form-control mb-3"
                        id="email"
                        name="email"
                        type="email"
                        maxlength="254"
                        required
                        value="<c:out value='${form.email}'/>">
                    <label class="form-label" for="role">Role</label>
                    <select class="form-select mb-3" id="role" name="role">
                        <c:forEach var="role" items="${roles}">
                            <option value="${role}" ${form.role == role.toString() ? 'selected' : ''}><c:out value="${role.displayName}"/></option>
                        </c:forEach>
                    </select>
                    <label class="form-label" for="active">State</label>
                    <select class="form-select mb-3" id="active" name="active">
                        <option value="true" ${form.active != 'false' ? 'selected' : ''}>Active</option>
                        <option value="false" ${form.active == 'false' ? 'selected' : ''}>Inactive</option>
                    </select>
                    <c:if test="${empty form.id}">
                        <label class="form-label" for="password">Initial password</label>
                        <input
                            class="form-control mb-3"
                            id="password"
                            name="password"
                            type="password"
                            autocomplete="new-password"
                            maxlength="72"
                            required>
                        <label class="form-label" for="confirmation">Confirm password</label>
                        <input
                            class="form-control mb-3"
                            id="confirmation"
                            name="confirmation"
                            type="password"
                            autocomplete="new-password"
                            maxlength="72"
                            required>
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
            <div class="table-responsive">
                <table class="table align-middle">
                    <thead>
                        <tr>
                            <th>ID</th>
                            <th>Account</th>
                            <th>Role</th>
                            <th>State</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <c:forEach var="user" items="${result.items}">
                            <tr>
                                <td><c:out value="${user.id}"/></td>
                                <td><c:out value="${user.fullName}"/><br><c:out value="${user.email}"/></td>
                                <td><c:out value="${user.role.displayName}"/></td>
                                <td><c:out value="${user.active ? 'Active' : 'Inactive'}"/></td>
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

/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.community.dialect;

import org.hibernate.dialect.DatabaseVersion;

/**
 * Dialect for Derby 10.7
 *
 * @author Strong Liu
 *
 * @deprecated use {@code DerbyLegacyDialect(1070)}
 */
@Deprecated
public class DerbyTenSevenDialect extends DerbyLegacyDialect {

	public DerbyTenSevenDialect() {
		super( DatabaseVersion.make( 10, 7 ) );
	}
}

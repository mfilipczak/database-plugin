package org.archicontribs.database.commandline;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.archicontribs.database.DBDatabaseEntry;
import org.archicontribs.database.DBLogger;
import org.archicontribs.database.DBPlugin;
import org.archicontribs.database.DBPlugin.CONFLICT_CHOICE;
import org.archicontribs.database.GUI.DBGui;
import org.archicontribs.database.GUI.DBGuiAdminDatabase;
import org.archicontribs.database.connection.DBDatabaseExportConnection;
import org.archicontribs.database.connection.DBDatabaseImportConnection;
import org.archicontribs.database.data.DBChecksum;
import org.archicontribs.database.data.DBCompoundCommand;
import org.archicontribs.database.data.DBImportMode;
import org.archicontribs.database.model.DBArchimateModel;
import org.archicontribs.database.model.DBMetadata;
import org.archicontribs.database.model.DBMetadata.DATABASE_STATUS;
import org.archicontribs.database.model.commands.DBDeleteDiagramConnectionCommand;
import org.archicontribs.database.model.commands.DBDeleteDiagramObjectCommand;
import org.archicontribs.database.model.commands.DBImportElementFromIdCommand;
import org.archicontribs.database.model.commands.DBImportFolderFromIdCommand;
import org.archicontribs.database.model.commands.DBImportRelationshipFromIdCommand;
import org.archicontribs.database.model.commands.DBImportViewConnectionFromIdCommand;
import org.archicontribs.database.model.commands.DBImportViewFromIdCommand;
import org.archicontribs.database.model.commands.DBImportViewObjectFromIdCommand;
import org.archicontribs.database.model.commands.DBResolveConnectionsCommand;
import org.archicontribs.database.model.commands.DBResolveRelationshipsCommand;
import org.archicontribs.database.model.commands.DBSetFolderToLastKnownCommand;
import org.archicontribs.database.model.commands.IDBCommand;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;

import com.archimatetool.commandline.AbstractCommandLineProvider;
import com.archimatetool.commandline.CommandLineState;
import com.archimatetool.editor.diagram.util.DiagramUtils;
import com.archimatetool.editor.diagram.util.ModelReferencedImage;
import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.commands.DeleteArchimateElementCommand;
import com.archimatetool.editor.model.commands.DeleteArchimateRelationshipCommand;
import com.archimatetool.editor.model.commands.DeleteDiagramModelCommand;
import com.archimatetool.editor.model.commands.DeleteFolderCommand;
import com.archimatetool.editor.ui.ImageFactory;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.impl.ArchimateModel;

/**
 * Command Line interface for importing a model from a database
 * 
 * Typical usage: Archi [-consoleLog] -application
 * com.archimatetool.commandline.app --export.database "database name"
 * --import.model.name "model name" [--import.model.version "model version"]
 * --export.conflicts "break/model/db"
 * --export.releaseNote "release Note"
 * 
 * @author Michal Filipczak
 */
public class DBExportModelProvider extends AbstractCommandLineProvider {
	protected static final DBLogger logger = new DBLogger(DBGuiAdminDatabase.class);

	static final String PREFIX = "DBPluginExport";
	static final String OPTION_EXPORT_DATABASE = "export.database";
	static final String OPTION_EXPORT_CONFLICTS = "export.conflicts";
	static final String OPTION_EXPORT_RELEASENOTE = "export.releaseNote";

	CommandStack stack;

	/**
	 * 
	 */
	public DBExportModelProvider() {

	}

	@Override
	public void run(CommandLine commandLine) throws Exception {
		logMessage(commandLine.toString());
		if (!hasCorrectOptions(commandLine)) {
			logError("Bad options provided.");
			return;
		}

		IArchimateModel model = CommandLineState.getModel();

		if (model == null) {
			throw new IOException("Brak modelu");
		}

		DBArchimateModel exportedModel = new DBArchimateModel((ArchimateModel) model);
		exportedModel.countAllObjects();
		logMessage("The model has got " + exportedModel.getAllElements().size() + " elements and "
				+ exportedModel.getAllRelationships().size() + " relationships and "
				+ exportedModel.getAllFolders().size() + " folders and " + exportedModel.getAllViews().size()
				+ " views and " + exportedModel.getAllViewObjects().size() + " objects and "
				+ exportedModel.getAllViewConnections().size() + " connections.");

		String databaseName = commandLine.getOptionValue(OPTION_EXPORT_DATABASE);
		List<DBDatabaseEntry> databaseEntries = DBDatabaseEntry.getAllDatabasesFromPreferenceStore();
		DBDatabaseEntry database = null;
		if ((databaseEntries == null) || (databaseEntries.size() == 0)) {
			logError(
					"You haven't configure any database yet.\n\nPlease setup at least one database in Archi preferences.");
		} else {
			for (DBDatabaseEntry databaseEntry : databaseEntries) {
				if (databaseEntry.getName().equals(databaseName)) {
					database = databaseEntry;
				}
			}
		}

		if (database == null) {
			logError("You haven't configure database >" + databaseName
					+ "< yet.\n\nPlease setup at least one database in Archi preferences.");
		}

		try(DBDatabaseExportConnection connection = new DBDatabaseExportConnection(database)) {

		String releaseNote = commandLine.getOptionValue(OPTION_EXPORT_RELEASENOTE, "");
		
		logMessage("Exporting model: " + exportedModel.getName());
		exportedModel.countAllObjects();
		DBCompoundCommand undoableCommands = new DBCompoundCommand("Sync model with database");
		this.stack = (CommandStack) model.getAdapter(CommandStack.class);
		try {
////////////////////////////PREPARATION PHASE : we calculate the model's checksum
		
		exportedModel.getCurrentVersion().setChecksum(DBChecksum.calculateChecksum(exportedModel, releaseNote));
		
		
		
		//////////////////////////// PHASE 1 : we compare the model to the database
		logMessage("we compare the model to the database");
		compareModelToDatabase(connection, exportedModel);
		
		//////////////////////////// PHASE 2 : we detect the conflicts and ask the user to resolve them

		if (true ) {
			logMessage("Checking for conflicts ...");

			Iterator<Entry<String, IArchimateElement>> elementsIterator = exportedModel.getAllElements().entrySet().iterator();
			while ( elementsIterator.hasNext() ) {
				IArchimateElement element = elementsIterator.next().getValue();
				if ( exportedModel.getDBMetadata(element).getDatabaseStatus() == DATABASE_STATUS.isConflicting ) {
					if ( exportedModel.getAllConflicts().get(element) != null ) {
						exportedModel.getAllConflicts().put(element, CONFLICT_CHOICE.askUser);
					}
				}
			}

			Iterator<Entry<String, IArchimateRelationship>> relationshipsIterator = exportedModel.getAllRelationships().entrySet().iterator();
			while ( relationshipsIterator.hasNext() ) {
				IArchimateRelationship relationship = relationshipsIterator.next().getValue();
				if ( exportedModel.getDBMetadata(relationship).getDatabaseStatus() == DATABASE_STATUS.isConflicting ) {
					if ( exportedModel.getAllConflicts().get(relationship) != null ) {
						exportedModel.getAllConflicts().put(relationship, CONFLICT_CHOICE.askUser);
					}
				}
			}

			Iterator<Entry<String, IDiagramModel>> viewsIterator = exportedModel.getAllViews().entrySet().iterator();
			while ( viewsIterator.hasNext() ) {
				IDiagramModel view = viewsIterator.next().getValue();
				if ( exportedModel.getDBMetadata(view).getDatabaseStatus() == DATABASE_STATUS.isConflicting ) {
					if ( exportedModel.getAllConflicts().get(view) != null ) {
						exportedModel.getAllConflicts().put(view, CONFLICT_CHOICE.askUser);
					}
				}
			}

			Iterator<Entry<String, IDiagramModelObject>> viewObjectsIterator = exportedModel.getAllViewObjects().entrySet().iterator();
			while ( viewObjectsIterator.hasNext() ) {
				IDiagramModelObject viewObject = viewObjectsIterator.next().getValue();
				if ( exportedModel.getDBMetadata(viewObject).getDatabaseStatus() == DATABASE_STATUS.isConflicting ) {
					if ( exportedModel.getAllConflicts().get(viewObject) != null ) {
						exportedModel.getAllConflicts().put(viewObject, CONFLICT_CHOICE.askUser);
					}
				}
			}

			Iterator<Entry<String, IDiagramModelConnection>> viewConnectionsIterator = exportedModel.getAllViewConnections().entrySet().iterator();
			while ( viewConnectionsIterator.hasNext() ) {
				IDiagramModelConnection viewConnection = viewConnectionsIterator.next().getValue();
				if (exportedModel.getDBMetadata(viewConnection).getDatabaseStatus() == DATABASE_STATUS.isConflicting ) {
					if (exportedModel.getAllConflicts().get(viewConnection) != null ) {
						exportedModel.getAllConflicts().put(viewConnection, CONFLICT_CHOICE.askUser);
					}
				}
			}

			// If there are some conflicts to resolve, 
			if (!exportedModel.getAllConflicts().isEmpty() ) {
				String conflictsOption = commandLine.getOptionValue(OPTION_EXPORT_CONFLICTS, "break");
				if("break".equalsIgnoreCase(conflictsOption)) {
					logError("Wykryto konflikty. Export zakoñczony niepowodzeniem");
					return;
				}else if("model".equalsIgnoreCase(conflictsOption)) {
					logError("Wykryto konflikty. Zastêpuje konflikty danymi z modelu");
					for(Map.Entry<EObject, CONFLICT_CHOICE> entry : exportedModel.getAllConflicts().entrySet()) {
						entry.setValue(CONFLICT_CHOICE.exportToDatabase);
					}
				}else if("db".equalsIgnoreCase(conflictsOption)) {
					logError("Wykryto konflikty. Zastêpuje konflikty danymi z bazy danych");
					for(Map.Entry<EObject, CONFLICT_CHOICE> entry : exportedModel.getAllConflicts().entrySet()) {
						entry.setValue(CONFLICT_CHOICE.importFromDatabase);
					}
				}  
			}
		}

		// if we're here, this means that there is no conflict or that all the conflicts have been resolved
		
////////////////////////////PHASE 3 : we remove from the model the components that have been deleted in the database 
			if (true) {

// please be aware that the commands put in the undoableCommand are single operations
// ie. when an element is deleted, the command does not delete at the same time the relationships connected to it, not the views objets that references it.

// we do not use getException() method as Archi commands do not implement it

				Iterator<Entry<String, IArchimateElement>> elementsIterator = exportedModel.getAllElements().entrySet()
						.iterator();
				while (elementsIterator.hasNext()) {
					IArchimateElement element = elementsIterator.next().getValue();
					if (exportedModel.getDBMetadata(element)
							.getDatabaseStatus() == DATABASE_STATUS.isDeletedInDatabase) {
						undoableCommands.add(new DeleteArchimateElementCommand(element));

					}
				}

				Iterator<Entry<String, IArchimateRelationship>> relationshipsIterator = exportedModel
						.getAllRelationships().entrySet().iterator();
				while (relationshipsIterator.hasNext()) {
					IArchimateRelationship relationship = relationshipsIterator.next().getValue();
					if (exportedModel.getDBMetadata(relationship)
							.getDatabaseStatus() == DATABASE_STATUS.isDeletedInDatabase) {
						undoableCommands.add(new DeleteArchimateRelationshipCommand(relationship));
					}
				}

				Iterator<Entry<String, IFolder>> foldersIterator = exportedModel.getAllFolders().entrySet().iterator();
				while (foldersIterator.hasNext()) {
					IFolder folder = foldersIterator.next().getValue();
					if (exportedModel.getDBMetadata(folder)
							.getDatabaseStatus() == DATABASE_STATUS.isDeletedInDatabase) {
						undoableCommands.add(new DeleteFolderCommand(folder));
					}
				}

				Iterator<Entry<String, IDiagramModel>> viewsIterator = exportedModel.getAllViews().entrySet()
						.iterator();
				while (viewsIterator.hasNext()) {
					IDiagramModel view = viewsIterator.next().getValue();
					if (exportedModel.getDBMetadata(view).getDatabaseStatus() == DATABASE_STATUS.isDeletedInDatabase) {
						undoableCommands.add(new DeleteDiagramModelCommand(view));
					}
				}

				Iterator<Entry<String, IDiagramModelObject>> viewObjectsIterator = exportedModel.getAllViewObjects()
						.entrySet().iterator();
				while (viewObjectsIterator.hasNext()) {
					IDiagramModelObject viewObject = viewObjectsIterator.next().getValue();
					if (exportedModel.getDBMetadata(viewObject)
							.getDatabaseStatus() == DATABASE_STATUS.isDeletedInDatabase) {
						undoableCommands.checkAndAdd(new DBDeleteDiagramObjectCommand(exportedModel, viewObject));
					}
				}

				Iterator<Entry<String, IDiagramModelConnection>> viewConnectionsIterator = exportedModel
						.getAllViewConnections().entrySet().iterator();
				while (viewConnectionsIterator.hasNext()) {
					IDiagramModelConnection viewConnection = viewConnectionsIterator.next().getValue();
					if (exportedModel.getDBMetadata(viewConnection)
							.getDatabaseStatus() == DATABASE_STATUS.isDeletedInDatabase) {
						undoableCommands
								.checkAndAdd(new DBDeleteDiagramConnectionCommand(exportedModel, viewConnection));
					}
				}

				if (!undoableCommands.isEmpty())
					undoableCommands.execute();
			}

//////////////////////////// PHASE 4 : we import new and updated components from the database
			if (true) {
// the commands are run in real time, but they are also added in the undoableCommands compound command because we want to be able to undo them
// they all implement the a getException() method that allow to check if an exception has been raised during the import

// we count the number of new components to import from the database 
				int progressBarWidth = connection.getFoldersNotInModel().size()
						+ connection.getElementsNotInModel().size() + connection.getRelationshipsNotInModel().size()
						+ connection.getViewsNotInModel().size() + connection.getViewObjectsNotInModel().size()
						+ connection.getViewConnectionsNotInModel().size();

// we add the number of updated components to import from the database
				Iterator<Entry<String, IFolder>> foldersIterator = exportedModel.getAllFolders().entrySet().iterator();
				while (foldersIterator.hasNext())
					if (exportedModel.getDBMetadata(foldersIterator.next().getValue())
							.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase)
						++progressBarWidth;

				Iterator<Entry<String, IArchimateElement>> elementsIterator = exportedModel.getAllElements().entrySet()
						.iterator();
				while (elementsIterator.hasNext())
					if (exportedModel.getDBMetadata(elementsIterator.next().getValue())
							.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase)
						++progressBarWidth;

				Iterator<Entry<String, IArchimateRelationship>> relationshipsIterator = exportedModel
						.getAllRelationships().entrySet().iterator();
				while (relationshipsIterator.hasNext())
					if (exportedModel.getDBMetadata(relationshipsIterator.next().getValue())
							.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase)
						++progressBarWidth;

				Iterator<Entry<String, IDiagramModel>> viewsIterator = exportedModel.getAllViews().entrySet()
						.iterator();
				while (viewsIterator.hasNext())
					if (exportedModel.getDBMetadata(viewsIterator.next().getValue())
							.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase)
						++progressBarWidth;

				Iterator<Entry<String, IDiagramModelObject>> viewObjectsIterator = exportedModel.getAllViewObjects()
						.entrySet().iterator();
				while (viewObjectsIterator.hasNext())
					if (exportedModel.getDBMetadata(viewObjectsIterator.next().getValue())
							.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase)
						++progressBarWidth;

				Iterator<Entry<String, IDiagramModelConnection>> viewConnectionsIterator = exportedModel
						.getAllViewConnections().entrySet().iterator();
				while (viewConnectionsIterator.hasNext())
					if (exportedModel.getDBMetadata(viewConnectionsIterator.next().getValue())
							.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase)
						++progressBarWidth;

// we count the number of conflict that have been resolved by an import from the database
				Iterator<Entry<EObject, CONFLICT_CHOICE>> conflictsIterator = exportedModel.getAllConflicts().entrySet()
						.iterator();
				while (conflictsIterator.hasNext()) {
					CONFLICT_CHOICE choice = conflictsIterator.next().getValue();
					if (choice == CONFLICT_CHOICE.importFromDatabase)
						++progressBarWidth;
				}

				if (progressBarWidth == 0)
					logMessage("There is no component to import from the database.");
				else {

					try (DBDatabaseImportConnection importConnection = new DBDatabaseImportConnection(connection)) {

						// IMPORT FOLDERS (we import the folders BEFORE the elements, relationships and
						// views because they must exist when the elements, relationships and views are
						// imported)
						if (connection.getFoldersNotInModel().size() == 0)
							logMessage("There is no folder to import.");
						else {
							logMessage("Importing new folders ...");
							for (String id : connection.getFoldersNotInModel().keySet()) {
								DBMetadata versionToImport = connection.getFoldersNotInModel().get(id);
								if (versionToImport.getInitialVersion().getVersion() == 0) {
									if (logger.isDebugEnabled())
										logger.debug("The folder id " + id
												+ " has been created in the database. We import it from the database.");
									undoableCommands.checkAndExecute(
											new DBImportFolderFromIdCommand(importConnection, exportedModel, null, id,
													versionToImport.getLatestDatabaseVersion().getVersion(),
													DBImportMode.forceSharedMode));

								} else {
									if (logger.isDebugEnabled())
										logger.debug("The folder id " + id
												+ " is not imported as it has been deleted from the model.");
								}
							}
						}

						// UPDATE FOLDERS
						foldersIterator = exportedModel.getAllFolders().entrySet().iterator();
						while (foldersIterator.hasNext()) {
							IFolder folder = foldersIterator.next().getValue();
							if (exportedModel.getDBMetadata(folder)
									.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase) {
								if (logger.isDebugEnabled())
									logger.debug("The folder id " + folder.getId()
											+ " has been updated in the database. We import the new version from the database.");
								undoableCommands.checkAndExecute(new DBImportFolderFromIdCommand(importConnection,
										exportedModel, null, folder.getId(), 0, DBImportMode.forceSharedMode));
							}
						}

						// IMPORT ELEMENTS
						if (connection.getElementsNotInModel().size() == 0)
							logMessage("There is no element to import.");
						else {
							logMessage("Importing new elements ...");
							for (String id : connection.getElementsNotInModel().keySet()) {
								DBMetadata versionToImport = connection.getElementsNotInModel().get(id);
								if (versionToImport.getInitialVersion().getVersion() == 0) {
									if (logger.isDebugEnabled())
										logger.debug("The element id " + id
												+ " has been created in the database. We import it from the database.");
									undoableCommands.checkAndExecute(
											new DBImportElementFromIdCommand(importConnection, exportedModel, null,
													null, id, versionToImport.getLatestDatabaseVersion().getVersion(),
													DBImportMode.forceSharedMode, false));

								} else {
									if (logger.isDebugEnabled())
										logger.debug("The element id " + id
												+ " is not imported as it has been deleted from the model.");
								}
							}
						}

						// UPDATE ELEMENTS
						elementsIterator = exportedModel.getAllElements().entrySet().iterator();
						while (elementsIterator.hasNext()) {
							IArchimateElement element = elementsIterator.next().getValue();
							if (exportedModel.getDBMetadata(element)
									.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase) {
								if (logger.isDebugEnabled())
									logger.debug("The element id " + element.getId()
											+ " has been updated in the database. We import the new version from the database.");
								undoableCommands.checkAndExecute(
										new DBImportElementFromIdCommand(importConnection, exportedModel, null, null,
												element.getId(), 0, DBImportMode.forceSharedMode, false));
							}
						}

						// IMPORT RELATIONSHIPS
						if (connection.getRelationshipsNotInModel().size() == 0)
							logMessage("There is no relationship to import.");
						else {
							logMessage("Importing new relationships ...");
							for (String id : connection.getRelationshipsNotInModel().keySet()) {
								DBMetadata versionToImport = connection.getRelationshipsNotInModel().get(id);
								if (versionToImport.getInitialVersion().getVersion() == 0) {
									if (logger.isDebugEnabled())
										logger.debug("The relationship id " + id
												+ " has been created in the database. We import it from the database.");
									undoableCommands.checkAndExecute(
											new DBImportRelationshipFromIdCommand(importConnection, exportedModel, null,
													null, id, versionToImport.getLatestDatabaseVersion().getVersion(),
													DBImportMode.forceSharedMode));

								} else {
									if (logger.isDebugEnabled())
										logger.debug("The relationship id " + id
												+ " is not imported as it has been deleted from the model.");

								}
							}
						}

						// UPDATE RELATIONSHIPS
						relationshipsIterator = exportedModel.getAllRelationships().entrySet().iterator();
						while (relationshipsIterator.hasNext()) {
							IArchimateRelationship relationship = relationshipsIterator.next().getValue();
							if (exportedModel.getDBMetadata(relationship)
									.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase) {
								if (logger.isDebugEnabled())
									logger.debug("The relationship id " + relationship.getId()
											+ " has been updated in the database. We import the new version from the database.");
								undoableCommands.checkAndExecute(
										new DBImportRelationshipFromIdCommand(importConnection, exportedModel, null,
												null, relationship.getId(), 0, DBImportMode.forceSharedMode));
							}
						}

						// IMPORT VIEWS
						if (connection.getViewsNotInModel().size() == 0)
							logMessage("There is no view to import.");
						else {
							logMessage("Importing new views ...");
							for (String id : connection.getViewsNotInModel().keySet()) {
								DBMetadata versionToImport = connection.getViewsNotInModel().get(id);
								if (versionToImport.getInitialVersion().getVersion() == 0) {
									if (logger.isDebugEnabled())
										logger.debug("The view id " + id
												+ " has been created in the database. We import it in the model.");
									undoableCommands.checkAndExecute(
											new DBImportViewFromIdCommand(importConnection, exportedModel, null, id,
													versionToImport.getLatestDatabaseVersion().getVersion(),
													DBImportMode.forceSharedMode, false));

								} else {
									if (logger.isDebugEnabled())
										logger.debug("The view id " + id
												+ " is not imported as it has been deleted from the model.");

								}
							}
						}

						// UPDATE VIEWS
						viewsIterator = exportedModel.getAllViews().entrySet().iterator();
						while (viewsIterator.hasNext()) {
							IDiagramModel view = viewsIterator.next().getValue();
							if (exportedModel.getDBMetadata(view)
									.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase) {
								if (logger.isDebugEnabled())
									logger.debug("The view id " + view.getId()
											+ " has been updated in the database. We import the new version from the database.");
								undoableCommands.checkAndExecute(new DBImportViewFromIdCommand(importConnection,
										exportedModel, null, view.getId(), 0, DBImportMode.forceSharedMode, false));

							}
						}

						// IMPORT VIEW OBJECTS
						if (connection.getViewObjectsNotInModel().size() == 0)
							logMessage("There is no view object to import.");
						else {
							logMessage("Importing new views objects ...");

							for (String id : connection.getViewObjectsNotInModel().keySet()) {
								DBMetadata versionToImport = connection.getViewObjectsNotInModel().get(id);
								if (versionToImport.getInitialVersion().getVersion() == 0) {
									if (logger.isDebugEnabled())
										logger.debug("The view object id " + id
												+ " has been created in the database. We import it in the model.");
									undoableCommands.checkAndExecute(
											new DBImportViewObjectFromIdCommand(importConnection, exportedModel, id,
													versionToImport.getLatestDatabaseVersion().getVersion(), false,
													DBImportMode.forceSharedMode));

								} else {
									if (logger.isDebugEnabled())
										logger.debug("The view object id " + id
												+ " is not imported as it has been deleted from the model.");
								}
							}
						}

						// UPDATE VIEW OBJECTS
						viewObjectsIterator = exportedModel.getAllViewObjects().entrySet().iterator();
						while (viewObjectsIterator.hasNext()) {
							IDiagramModelObject viewObject = viewObjectsIterator.next().getValue();
							if (exportedModel.getDBMetadata(viewObject)
									.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase) {
								if (logger.isDebugEnabled())
									logger.debug("The view object id " + viewObject.getId()
											+ " has been updated in the database. We import the new version from the database.");
								undoableCommands.checkAndExecute(new DBImportViewObjectFromIdCommand(importConnection,
										exportedModel, viewObject.getId(), 0, false, DBImportMode.forceSharedMode));
							}
						}

						// IMPORT VIEW CONNECTIONS
						if (connection.getViewConnectionsNotInModel().size() == 0)
							logMessage("There is no view connection to import.");
						else {
							logMessage("Importing new views connections ...");

							for (String id : connection.getViewConnectionsNotInModel().keySet()) {
								DBMetadata versionToImport = connection.getViewConnectionsNotInModel().get(id);
								if (versionToImport.getInitialVersion().getVersion() == 0) {
									if (logger.isDebugEnabled())
										logger.debug("The view connection id " + id
												+ " has been created in the database. We import it in the model.");
									undoableCommands.checkAndExecute(
											new DBImportViewConnectionFromIdCommand(importConnection, exportedModel, id,
													versionToImport.getLatestDatabaseVersion().getVersion(), false,
													DBImportMode.forceSharedMode));

								} else {
									if (logger.isDebugEnabled())
										logger.debug("The view connection id " + id
												+ " is not imported as it has been deleted from the model.");

								}
							}
						}

						// UPDATE VIEW CONNECTIONS
						viewConnectionsIterator = exportedModel.getAllViewConnections().entrySet().iterator();
						while (viewConnectionsIterator.hasNext()) {
							IDiagramModelConnection viewConnection = viewConnectionsIterator.next().getValue();
							if (exportedModel.getDBMetadata(viewConnection)
									.getDatabaseStatus() == DATABASE_STATUS.isUpadtedInDatabase) {
								if (logger.isDebugEnabled())
									logger.debug("The view connection id " + viewConnection.getId()
											+ " has been updated in the database. We import the new version from the database.");
								undoableCommands.checkAndExecute(
										new DBImportViewConnectionFromIdCommand(importConnection, exportedModel,
												viewConnection.getId(), 0, false, DBImportMode.forceSharedMode));

							}
						}

						conflictsIterator = exportedModel.getAllConflicts().entrySet().iterator();
						while (conflictsIterator.hasNext()) {
							Entry<EObject, CONFLICT_CHOICE> entry = conflictsIterator.next();
							EObject componentToImport = entry.getKey();
							CONFLICT_CHOICE choice = entry.getValue();

							if (choice == CONFLICT_CHOICE.importFromDatabase) {
								IDBCommand command = null;
								String id = ((IIdentifier) componentToImport).getId();
								DBMetadata metadata = exportedModel.getDBMetadata(componentToImport);
								int latestDatabaseVersion = metadata.getLatestDatabaseVersion().getVersion();

								if (componentToImport instanceof IArchimateElement) {
									command = new DBImportElementFromIdCommand(importConnection, exportedModel, null,
											null, id, latestDatabaseVersion, DBImportMode.forceSharedMode, false);

								} else if (componentToImport instanceof IArchimateRelationship) {
									command = new DBImportRelationshipFromIdCommand(importConnection, exportedModel,
											null, null, id, latestDatabaseVersion, DBImportMode.forceSharedMode);

								} else if (componentToImport instanceof IDiagramModel) {
									command = new DBImportViewFromIdCommand(importConnection, exportedModel, null, id,
											latestDatabaseVersion, DBImportMode.forceSharedMode, false);

								} else if (componentToImport instanceof IDiagramModelObject) {
									command = new DBImportViewObjectFromIdCommand(importConnection, exportedModel, id,
											latestDatabaseVersion, false, DBImportMode.forceSharedMode);

								} else if (componentToImport instanceof IDiagramModelConnection) {
									command = new DBImportViewConnectionFromIdCommand(importConnection, exportedModel,
											id, latestDatabaseVersion, false, DBImportMode.forceSharedMode);

								}

								if (logger.isDebugEnabled())
									logger.debug("The conflicting " + metadata.getDebugName()
											+ " conflicts with the database, but the conflict resolution has been set to "
											+ CONFLICT_CHOICE.importFromDatabase);
								undoableCommands.checkAndExecute(command);
							}
						}

						// RESOLVE RELATIONSHIPS
						if ((exportedModel.getAllSourceRelationshipsToResolve().size() != 0)
								|| (exportedModel.getAllTargetRelationshipsToResolve().size() != 0)) {
							undoableCommands.checkAndExecute(new DBResolveRelationshipsCommand(exportedModel));
						}

						// RESOLVE CONNECTIONS
						if ((exportedModel.getAllSourceConnectionsToResolve().size() != 0)
								|| (exportedModel.getAllTargetConnectionsToResolve().size() != 0)) {
							undoableCommands.checkAndExecute(new DBResolveConnectionsCommand(exportedModel));
						}
					}
				}
			}

//////////////////////////// PHASE 5 : we move components to new folders if they've been moved in the database
			if (true) {

				try (DBDatabaseImportConnection importConnection = new DBDatabaseImportConnection(connection)) {
					DBSetFolderToLastKnownCommand setFolderCommand = new DBSetFolderToLastKnownCommand(exportedModel,
							importConnection);
					if (setFolderCommand.getException() != null)
						throw setFolderCommand.getException();
					if (setFolderCommand.needsToBeExecuted()) {
						logMessage("Moving components to new folders");
						undoableCommands.checkAndExecute(setFolderCommand);
					} else
						logMessage("There is no component to move to a new folder.");
				}
			}

//////////////////////////// PHASE 6 : we recalculate all the checksums and the "getAll..."  maps as all the containers may have changed because of imported and deleted components
			if (true && !undoableCommands.isEmpty()) {

// recalculate the checksum. This does not update the versions, so the database status remains.
				exportedModel.countAllObjects();
			}

////////////////////////////PHASE 7 : we re-compare the model to the database as the imports may have been sufficient
			if (true && !undoableCommands.isEmpty()) {
				if (compareModelToDatabase(connection, exportedModel)) {

					logMessage("Your model is now in sync with the database.");
// TODO: loop on getAllXXX to fill in the txtTotalXXXX fields
					return;
				}
			}

//////////////////////////// PHASE 8 : at last, we export a new version of the model to the database

// we start a new database transaction
			connection.setAutoCommit(false);

				logMessage("Exporting the model itslef ...");
				connection.exportModel(exportedModel, releaseNote);// this.txtReleaseNote.getText());


// EXPORT ELEMENTS

			Iterator<Entry<String, IArchimateElement>> elementsIterator = exportedModel.getAllElements().entrySet()
					.iterator();
			while (elementsIterator.hasNext()) {
				EObject componentToExport = elementsIterator.next().getValue();
				DATABASE_STATUS dbStatus = exportedModel.getDBMetadata(componentToExport).getDatabaseStatus();
				if (dbStatus == DATABASE_STATUS.isNewInModel)
					connection.exportEObject(componentToExport);
				else if (dbStatus == DATABASE_STATUS.isUpdatedInModel)
					connection.exportEObject(componentToExport);
				connection.assignEObjectToModel(componentToExport);

			}

// EXPORT RELATIONSHIPS

			Iterator<Entry<String, IArchimateRelationship>> relationshipsIterator = exportedModel.getAllRelationships()
					.entrySet().iterator();
			while (relationshipsIterator.hasNext()) {
				EObject componentToExport = relationshipsIterator.next().getValue();

				DATABASE_STATUS dbStatus = exportedModel.getDBMetadata(componentToExport).getDatabaseStatus();
				if (dbStatus == DATABASE_STATUS.isNewInModel)
					connection.exportEObject(componentToExport);
				else if (dbStatus == DATABASE_STATUS.isUpdatedInModel)
					connection.exportEObject(componentToExport);
				connection.assignEObjectToModel(componentToExport);

			}

			if (true) {

				Iterator<Entry<String, IFolder>> foldersIterator = exportedModel.getAllFolders().entrySet().iterator();
				while (foldersIterator.hasNext()) {
					EObject componentToExport = foldersIterator.next().getValue();
					DATABASE_STATUS dbStatus = exportedModel.getDBMetadata(componentToExport).getDatabaseStatus();
					if (dbStatus == DATABASE_STATUS.isNewInModel)
						connection.exportEObject(componentToExport);
					else if (dbStatus == DATABASE_STATUS.isUpdatedInModel)
						connection.exportEObject(componentToExport);

					connection.assignEObjectToModel(componentToExport);

				}

				Iterator<Entry<String, IDiagramModel>> viewsIterator = exportedModel.getAllViews().entrySet()
						.iterator();
				while (viewsIterator.hasNext()) {
					EObject componentToExport = viewsIterator.next().getValue();
					String txtFieldToIncrement = null;
					DBMetadata metadata = exportedModel.getDBMetadata(componentToExport);
					DATABASE_STATUS dbStatus = metadata.getDatabaseStatus();
					if (dbStatus == DATABASE_STATUS.isNewInModel)
						txtFieldToIncrement = "";
					else if (dbStatus == DATABASE_STATUS.isUpdatedInModel)
						txtFieldToIncrement = "";

					if (txtFieldToIncrement != null) {
						if (metadata.getScreenshot().isScreenshotActive()) {
							createImage((IDiagramModel) componentToExport,
									connection.getDatabaseEntry().getViewsImagesScaleFactor(),
									connection.getDatabaseEntry().getViewsImagesBorderWidth());

						}
						connection.exportEObject(componentToExport);
						metadata.setExported(true);
					} else
						metadata.setExported(false);

					connection.assignEObjectToModel(componentToExport);

				}

				Iterator<Entry<String, IDiagramModelObject>> viewObjectsIterator = exportedModel.getAllViewObjects()
						.entrySet().iterator();
				while (viewObjectsIterator.hasNext()) {
					IDiagramModelObject componentToExport = viewObjectsIterator.next().getValue();

					if (exportedModel.getDBMetadata(componentToExport.getDiagramModel()).isExported()) {
						DATABASE_STATUS dbStatus = exportedModel.getDBMetadata(componentToExport).getDatabaseStatus();
						if (dbStatus == DATABASE_STATUS.isNewInModel)
							connection.exportEObject(componentToExport);
						else if (dbStatus == DATABASE_STATUS.isUpdatedInModel)
							connection.exportEObject(componentToExport);

						connection.assignEObjectToModel(componentToExport);
					}

				}

				logMessage("Exporting view connections ...");
				Iterator<Entry<String, IDiagramModelConnection>> viewConnectionsIterator = exportedModel
						.getAllViewConnections().entrySet().iterator();
				while (viewConnectionsIterator.hasNext()) {
					IDiagramModelConnection componentToExport = viewConnectionsIterator.next().getValue();

					if (exportedModel.getDBMetadata(componentToExport.getDiagramModel()).isExported()) {
						DATABASE_STATUS dbStatus = exportedModel.getDBMetadata(componentToExport).getDatabaseStatus();
						if (dbStatus == DATABASE_STATUS.isNewInModel)
							connection.exportEObject(componentToExport);
						else if (dbStatus == DATABASE_STATUS.isUpdatedInModel)
							connection.exportEObject(componentToExport);

						connection.assignEObjectToModel(componentToExport);
					}

				}

				logMessage("Exporting images ...");
// no need to use imagesNotInModel as the requested images have been imported at the same time as their view object
				IArchiveManager archiveMgr = (IArchiveManager) exportedModel.getAdapter(IArchiveManager.class);
				for (String path : exportedModel.getAllImagePaths()) {
					connection.exportImage(path, archiveMgr.getBytesFromEntry(path));
				}

// we register the undoableCommands on the model's stack, this way, the user will be able to manually undo them
				this.stack.execute(undoableCommands);
			}
		} catch (Exception exportError) {
// if the user clicked on the "cancel" button, then the database connection is closed, which generates an exception when a SQL request is executed
			try {
				rollbackAndCloseConnection(connection);

				logError(
						"The transaction has been rolled back to leave the database in a coherent state. You may solve the issue and export again your components."
								+ exportError.getMessage());
				exportError.printStackTrace();
			} catch (SQLException closeDBError) {
				logError("Error while exporting model.\n" + closeDBError.getMessage()
						+ "\nThe transaction failed to rollback, please check your database carrefully !");
			}

// we rollback any update done on the model
			if (!undoableCommands.isEmpty()) {
				this.stack.undo();
// this.undoableCommands.undo();
				for (Object cmd : undoableCommands.getCommands()) {
					try {
						Method getException = IDBCommand.class.getMethod("getException()");
						Exception e = (Exception) getException.invoke(cmd);
						if (e != null) {
							// a single message is sufficient to alert the user
							break;
						}
					} catch (@SuppressWarnings("unused") Exception ign) {
						// nothing to do if the command does not have manage exceptions
					}
				}
			}

			return;
		}

// if we're here, it means that no exception has been raised during the export process
		try {
			commitAndCloseConnection(connection);

			logMessage("*** Export successful ***");
		} catch (Exception err) {

			logError("Failed to commit the database transaction.\n" + err.getMessage()
					+ "\nPlease check your database carrefully.");
			return;
		}
		}
	}

	@SuppressWarnings("static-method")
	public void commitAndCloseConnection(DBDatabaseExportConnection connection) throws SQLException {
		if (connection != null) {
			// in case some transactions have been started, we commit them
			connection.commit();

			connection.close();
		}
	}

	@SuppressWarnings("static-method")
	public void rollbackAndCloseConnection(DBDatabaseExportConnection connection) throws SQLException {
		if (connection != null) {
			// in case some transactions have been started, we roll them back
			connection.rollback();

			connection.close();

		}
	}

	@SuppressWarnings("static-method")
	public byte[] createImage(IDiagramModel view, int scalePercent, int margin) {
		byte[] imageContent = null;
		DBMetadata dbMetadata = DBMetadata.getDBMetadata(view);

		logger.debug(DBGui.class, "Creating screenshot of view \"" + view.getName() + "\"");

		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			try (DataOutputStream writeOut = new DataOutputStream(out)) {
				ImageLoader saver = new ImageLoader();
				ModelReferencedImage viewImage = DiagramUtils.createModelReferencedImage(view, scalePercent / 100.0,
						margin);
				Image image = viewImage.getImage();

				saver.data = new ImageData[] { image.getImageData(ImageFactory.getDeviceZoom()) };
				image.dispose();

				saver.save(writeOut, SWT.IMAGE_PNG);
				imageContent = out.toByteArray();

				org.eclipse.draw2d.geometry.Rectangle bounds = viewImage.getBounds();
				bounds.performScale(ImageFactory.getDeviceZoom() / 100); // Account for device zoom level

				dbMetadata.getScreenshot().setScreenshotBytes(imageContent);
				dbMetadata.getScreenshot().setScaleFactor(scalePercent);
				dbMetadata.getScreenshot().setBorderWidth(margin);
				dbMetadata.getScreenshot().setBounds(bounds);
			} catch (IOException err) {
				logger.error(DBGui.class, "Failed to close DataOutputStream", err);
			}
		} catch (IOException err) {
			logger.error(DBGui.class, "Failed to close ByteArrayOutputStream", err);
		}

		return imageContent;
	}

	private boolean compareModelToDatabase(DBDatabaseExportConnection exportConnection, DBArchimateModel exportedModel)
			throws Exception {
		// We do not verify the content of neo4j database, we just export the components

		try {
			// we compare the elements, relationships, folders and views
			exportConnection.getAllVersionFromDatabase(exportedModel, null);
		} catch (SQLException err) {
			logError("Error while exporting model.\n" + err.getMessage());
			return false;
		}

		// we create the view screenshots if the database is configured to export them
		Iterator<Entry<String, IDiagramModel>> screenshotsIterator = exportedModel.getAllViews().entrySet().iterator();
		while (screenshotsIterator.hasNext()) {
			IDiagramModel view = screenshotsIterator.next().getValue();
			DBMetadata metadata = exportedModel.getDBMetadata(view);
			if (exportConnection.getDatabaseEntry().isViewSnapshotRequired()) {
				if ((metadata.getScreenshot().getBytes() == null)
						|| (metadata.getScreenshot().getScaleFactor() != exportConnection.getDatabaseEntry()
								.getViewsImagesScaleFactor())
						|| (metadata.getScreenshot().getBodrderWidth() != exportConnection.getDatabaseEntry()
								.getViewsImagesBorderWidth())) {

					createImage(view, exportConnection.getDatabaseEntry().getViewsImagesScaleFactor(),
							exportConnection.getDatabaseEntry().getViewsImagesBorderWidth());

				}
				metadata.getScreenshot().setScreenshotActive(true);
			} else
				metadata.getScreenshot().setScreenshotActive(false);

			// we recalculate the view checksum using the screenshot (or not)
			exportedModel.countObject(view, true);
		}

		logMessage("Calculating number of new, updated and deleted components.");

		int total = 0;

		int nbNew = 0;
		int nbNewInDb = 0;
		int nbUpdated = 0;
		int nbUpdatedInDb = 0;
		int nbConflict = 0;
		int nbDeleted = 0;
		int nbDeletedInDb = 0;
		Iterator<Map.Entry<String, IArchimateElement>> elementsIterator = exportedModel.getAllElements().entrySet()
				.iterator();
		while (elementsIterator.hasNext()) {
			IArchimateElement element = elementsIterator.next().getValue();
			DBMetadata metadata = exportedModel.getDBMetadata(element);
			switch (metadata.getDatabaseStatus()) {
			case isNewInModel:
				++nbNew;
				break;
			case isUpadtedInDatabase:
				++nbUpdatedInDb;
				break;
			case isUpdatedInModel:
				++nbUpdated;
				break;
			case isDeletedInDatabase:
				++nbDeletedInDb;
				break;
			case isConflicting:
				if (exportedModel.getAllConflicts().get(element) == null)
					exportedModel.getAllConflicts().put(element, CONFLICT_CHOICE.askUser);
				switch (exportedModel.getAllConflicts().get(element)) {
				case doNotExport: // nothing to do
					break;
				case exportToDatabase:
					++nbUpdated;
					break;
				case importFromDatabase:
					++nbUpdatedInDb;
					break;
				case askUser:
				default:
					++nbConflict;
				}
				break;
			case isSynced:
				// nothing to do
				break;
			case isNewInDatabase:
			default:
				// should never be here
			}
		}
		// we distinguish the elements new in the database from those deleted from
		// memory
		for (DBMetadata metadata : exportConnection.getElementsNotInModel().values()) {
			if (metadata.getInitialVersion().getVersion() != 0)
				++nbDeleted; // if the component did exist in the InitialVersion of the model, then it has
								// been deleted from the model
			else
				++nbNewInDb; // else, the component has been created in the database since the model has been
								// loaded

		}
		total += nbNew + nbNewInDb + nbUpdated + nbUpdatedInDb + nbDeleted + nbDeletedInDb + nbConflict;

		nbNew = 0;
		nbNewInDb = 0;
		nbUpdated = 0;
		nbUpdatedInDb = 0;
		nbConflict = 0;
		nbDeleted = 0;
		nbDeletedInDb = 0;
		Iterator<Map.Entry<String, IArchimateRelationship>> relationshipsIterator = exportedModel.getAllRelationships()
				.entrySet().iterator();
		while (relationshipsIterator.hasNext()) {
			IArchimateRelationship relationship = relationshipsIterator.next().getValue();
			DBMetadata metadata = exportedModel.getDBMetadata(relationship);
			switch (metadata.getDatabaseStatus()) {
			case isNewInModel:
				++nbNew;
				break;
			case isUpadtedInDatabase:
				++nbUpdatedInDb;
				break;
			case isUpdatedInModel:
				++nbUpdated;
				break;
			case isDeletedInDatabase:
				++nbDeletedInDb;
				break;
			case isConflicting:
				if (exportedModel.getAllConflicts().get(relationship) == null)
					exportedModel.getAllConflicts().put(relationship, CONFLICT_CHOICE.askUser);
				switch (exportedModel.getAllConflicts().get(relationship)) {
				case doNotExport: // nothing to do
					break;
				case exportToDatabase:
					++nbUpdated;
					break;
				case importFromDatabase:
					++nbUpdatedInDb;
					break;
				case askUser:
				default: // askUSer
					++nbConflict;
				}
				break;
			case isSynced:
				// nothing to do
				break;
			case isNewInDatabase:
			default:
				// should never be here
			}
		}
		// we distinguish the relationships new in the database from those deleted from
		// memory
		for (DBMetadata metadata : exportConnection.getRelationshipsNotInModel().values()) {
			if (metadata.getInitialVersion().getVersion() != 0)
				++nbDeleted; // if the component did exist in the InitialVersion of the model, then it has
								// been deleted from the model
			else
				++nbNewInDb; // else, the component has been created in the database since the model has been
								// loaded
		}
		total += nbNew + nbNewInDb + nbUpdated + nbUpdatedInDb + nbDeleted + nbDeletedInDb + nbConflict;

		nbNew = 0;
		nbNewInDb = 0;
		nbUpdated = 0;
		nbUpdatedInDb = 0;
		nbConflict = 0;
		nbDeleted = 0;
		nbDeletedInDb = 0;
		Iterator<Map.Entry<String, IFolder>> folderIterator = exportedModel.getAllFolders().entrySet().iterator();
		while (folderIterator.hasNext()) {
			IFolder folder = folderIterator.next().getValue();
			DBMetadata metadata = exportedModel.getDBMetadata(folder);
			switch (metadata.getDatabaseStatus()) {
			case isNewInModel:
				++nbNew;
				break;
			case isUpadtedInDatabase:
				++nbUpdatedInDb;
				break;
			case isUpdatedInModel:
				++nbUpdated;
				break;
			case isDeletedInDatabase:
				++nbDeletedInDb;
				break;
			case isConflicting:
				// There is no conflict for folders: conflicts are managed with their content
				// If a folder has been updated both in the model and the database, then we
				// export a new version
				++nbUpdated;
				break;
			case isSynced:
				// nothing to do
				break;
			case isNewInDatabase:
			default:
				// should never be here
			}
		}
		// we distinguish the folders new in the database from those deleted from memory
		for (DBMetadata metadata : exportConnection.getFoldersNotInModel().values()) {
			if (metadata.getInitialVersion().getVersion() != 0)
				++nbDeleted; // if the component did exist in the InitialVersion of the model, then it has
								// been deleted from the model
			else
				++nbNewInDb; // else, the component has been created in the database since the model has been
								// loaded
		}
		total += nbNew + nbNewInDb + nbUpdated + nbUpdatedInDb + nbDeleted + nbDeletedInDb + nbConflict;

		nbNew = 0;
		nbNewInDb = 0;
		nbUpdated = 0;
		nbUpdatedInDb = 0;
		nbConflict = 0;
		nbDeleted = 0;
		nbDeletedInDb = 0;
		Iterator<Map.Entry<String, IDiagramModel>> viewsIterator = exportedModel.getAllViews().entrySet().iterator();
		while (viewsIterator.hasNext()) {
			IDiagramModel view = viewsIterator.next().getValue();
			DBMetadata metadata = exportedModel.getDBMetadata(view);
			switch (metadata.getDatabaseStatus()) {
			case isNewInModel:
				++nbNew;
				break;
			case isUpadtedInDatabase:
				++nbUpdatedInDb;
				break;
			case isUpdatedInModel:
				++nbUpdated;
				break;
			case isDeletedInDatabase:
				++nbDeletedInDb;
				break;
			case isConflicting:
				if (exportedModel.getAllConflicts().get(view) == null)
					exportedModel.getAllConflicts().put(view, CONFLICT_CHOICE.askUser);
				switch (exportedModel.getAllConflicts().get(view)) {
				case doNotExport: // nothing to do
					break;
				case exportToDatabase:
					++nbUpdated;
					break;
				case importFromDatabase:
					++nbUpdatedInDb;
					break;
				case askUser:
				default: // askUSer
					++nbConflict;
				}
				break;
			case isSynced:
				// nothing to do
				break;
			case isNewInDatabase:
			default:
				// should never be here
			}
		}
		// we distinguish the views new in the database from those deleted from memory
		for (DBMetadata metadata : exportConnection.getViewsNotInModel().values()) {
			if (metadata.getInitialVersion().getVersion() != 0)
				++nbDeleted; // if the component did exist in the InitialVersion of the model, then it has
								// been deleted from the model
			else
				++nbNewInDb; // else, the component has been created in the database since the model has been
								// loaded
		}
		total += nbNew + nbNewInDb + nbUpdated + nbUpdatedInDb + nbDeleted + nbDeletedInDb + nbConflict;

		nbNew = 0;
		nbNewInDb = 0;
		nbUpdated = 0;
		nbUpdatedInDb = 0;
		nbConflict = 0;
		nbDeleted = 0;
		nbDeletedInDb = 0;
		Iterator<Map.Entry<String, IDiagramModelObject>> viewObjectsIterator = exportedModel.getAllViewObjects()
				.entrySet().iterator();
		while (viewObjectsIterator.hasNext()) {
			IDiagramModelObject viewObject = viewObjectsIterator.next().getValue();
			DBMetadata metadata = exportedModel.getDBMetadata(viewObject);
			switch (metadata.getDatabaseStatus()) {
			case isNewInModel:
				++nbNew;
				break;
			case isUpadtedInDatabase:
				++nbUpdatedInDb;
				break;
			case isUpdatedInModel:
				++nbUpdated;
				break;
			case isDeletedInDatabase:
				++nbDeletedInDb;
				break;
			case isConflicting:
				if (exportedModel.getAllConflicts().get(viewObject) == null)
					exportedModel.getAllConflicts().put(viewObject, CONFLICT_CHOICE.askUser);
				switch (exportedModel.getAllConflicts().get(viewObject)) {
				case doNotExport: // nothing to do
					break;
				case exportToDatabase:
					++nbUpdated;
					break;
				case importFromDatabase:
					++nbUpdatedInDb;
					break;
				case askUser:
				default: // askUSer
					++nbConflict;
				}
				break;
			case isSynced:
				// nothing to do
				break;
			case isNewInDatabase:
			default:
				// should never be here
			}
		}
		// we distinguish the viewObjects new in the database from those deleted from
		// memory
		for (DBMetadata metadata : exportConnection.getViewObjectsNotInModel().values()) {
			if (metadata.getInitialVersion().getVersion() != 0)
				++nbDeleted; // if the component did exist in the InitialVersion of the model, then it has
								// been deleted from the model
			else
				++nbNewInDb; // else, the component has been created in the database since the model has been
								// loaded
		}
		total += nbNew + nbNewInDb + nbUpdated + nbUpdatedInDb + nbDeleted + nbDeletedInDb + nbConflict;

		nbNew = 0;
		nbNewInDb = 0;
		nbUpdated = 0;
		nbUpdatedInDb = 0;
		nbConflict = 0;
		nbDeleted = 0;
		nbDeletedInDb = 0;
		Iterator<Map.Entry<String, IDiagramModelConnection>> viewConnectionsIterator = exportedModel
				.getAllViewConnections().entrySet().iterator();
		while (viewConnectionsIterator.hasNext()) {
			IDiagramModelConnection viewConnection = viewConnectionsIterator.next().getValue();
			DBMetadata metadata = exportedModel.getDBMetadata(viewConnection);
			switch (metadata.getDatabaseStatus()) {
			case isNewInModel:
				++nbNew;
				break;
			case isUpadtedInDatabase:
				++nbUpdatedInDb;
				break;
			case isUpdatedInModel:
				++nbUpdated;
				break;
			case isDeletedInDatabase:
				++nbDeletedInDb;
				break;
			case isConflicting:
				if (exportedModel.getAllConflicts().get(viewConnection) == null)
					exportedModel.getAllConflicts().put(viewConnection, CONFLICT_CHOICE.askUser);
				switch (exportedModel.getAllConflicts().get(viewConnection)) {
				case doNotExport: // nothing to do
					break;
				case exportToDatabase:
					++nbUpdated;
					break;
				case importFromDatabase:
					++nbUpdatedInDb;
					break;
				case askUser:
				default: // askUSer
					++nbConflict;
				}
				break;
			case isSynced:
				// nothing to do
				break;
			case isNewInDatabase:
			default:
				// should never be here
			}
		}
		// we distinguish the ViewConnections new in the database from those deleted
		// from memory
		for (DBMetadata metadata : exportConnection.getViewConnectionsNotInModel().values()) {
			if (metadata.getInitialVersion().getVersion() != 0)
				++nbDeleted; // if the component did exist in the InitialVersion of the model, then it has
								// been deleted from the model
			else
				++nbNewInDb; // else, the component has been created in the database since the model has been
								// loaded
		}
		total += nbNew + nbNewInDb + nbUpdated + nbUpdatedInDb + nbDeleted + nbDeletedInDb + nbConflict;

		if (total == 0) {
			logMessage("The model does not need to be exported to the database.");
			return true;
		}

		logMessage("The model needs to be exported to the database. Total Elements" + total);

		return false;
	}

	@Override
	public Options getOptions() {
		Options options = new Options();

		Option option = Option.builder().longOpt(OPTION_EXPORT_DATABASE).hasArg().argName("database").desc(
				"Name of the database (should already be configured in Archi through the database plugin preferences page)")
				.build();
		options.addOption(option);
		option = Option.builder().longOpt(OPTION_EXPORT_CONFLICTS).hasArg().argName("conflicts").desc(
				"Resolve conflicts method break/model/db")
				.build();
		options.addOption(option);
		
		option = Option.builder().longOpt(OPTION_EXPORT_RELEASENOTE).hasArg().argName("releaseNote").desc(
				"Release note")
				.build();
		options.addOption(option);
		
		return options;
	}

	@SuppressWarnings("static-method")
	private boolean hasCorrectOptions(CommandLine commandLine) {
		return commandLine.hasOption(OPTION_EXPORT_DATABASE)
				&& !DBPlugin.isEmpty(commandLine.getOptionValue(OPTION_EXPORT_DATABASE));
	}

	@Override
	public int getPriority() {
		return PRIORITY_REPORT_OR_EXPORT;
	}

	@Override
	protected String getLogPrefix() {
		return PREFIX;
	}
}
